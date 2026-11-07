package dev.redflag.app.debug

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.redflag.app.graph
import dev.redflag.app.lite.LiteRtGemmaEngine
import dev.redflag.app.ui.theme.RedFlagTheme
import dev.redflag.app.ui.theme.RfType
import dev.redflag.core.gemma.GemmaOutputParser
import dev.redflag.core.gemma.GemmaPipeline
import dev.redflag.core.model.Features
import dev.redflag.core.gemma.PromptBuilder
import dev.redflag.core.model.GemmaInput
import dev.redflag.core.model.RedFlagJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs REAL Gemma (LiteRT-LM, same settings as the app) over a pushed cases.jsonl and writes one
 * run_eval.py-compatible record per case/run to files/bench/results-<tag>.jsonl:
 *   {id, run, label, latency (s), completion_tokens, prompt_tokens, thought, clean, parse_error,
 *    output, raw, schema_errors, backend, device, spec_decoding}
 * Score on the Mac with eval/score_device_results.py.
 *
 * adb shell am start -n dev.redflag.app/.debug.DebugBenchmarkActivity \
 *   --es cases cases.jsonl --ei runs 1 --es tag s26-gpu --ez spec true [--es backend gpu|cpu]
 */
class DebugBenchmarkActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RedFlagTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("RedFlag on-device benchmark", style = RfType.title)
                    lines.forEach { Text(it, style = RfType.mono, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        val casesName = intent.getStringExtra("cases") ?: "cases.jsonl"
        val runs = intent.getIntExtra("runs", 1).coerceIn(1, 10)
        val spec = intent.getBooleanExtra("spec", true)
        val tag = intent.getStringExtra("tag") ?: SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backend = intent.getStringExtra("backend")?.lowercase()?.takeIf { it == "gpu" || it == "cpu" }
        lifecycleScope.launch { run(casesName, runs, spec, tag, backend) }
    }

    private fun log(s: String) { Log.i(TAG, s); lines += s }

    private suspend fun run(casesName: String, runs: Int, spec: Boolean, tag: String, backend: String?) {
        val g = graph
        val base = getExternalFilesDir(null)!!
        val casesFile = File(base, casesName)
        if (!casesFile.exists()) { log("missing ${casesFile.absolutePath} (adb push it)"); return }
        val engine = LiteRtGemmaEngine(this, speculative = { spec }, forceBackend = backend)
        if (!engine.modelAvailable()) { log("model missing: ${engine.modelFile().absolutePath}"); return }
        val outDir = File(base, "bench").apply { mkdirs() }
        val out = File(outDir, "results-$tag.jsonl")
        log("cases=${casesFile.name} runs=$runs spec=$spec backend=${backend ?: "auto"} -> ${out.absolutePath}")

        val t0 = System.currentTimeMillis()
        val init = withContext(Dispatchers.Default) { engine.warmUp() }
        if (init.isFailure) { log("engine init failed: ${init.exceptionOrNull()?.message}"); return }
        log("engine ready on ${engine.backendLabel} in ${System.currentTimeMillis() - t0} ms")

        val system = g.bundle.systemPrompt
        out.bufferedWriter().use { w ->
            for (line in casesFile.readLines().filter { it.isNotBlank() }) {
                val case = RedFlagJson.parseToJsonElement(line).jsonObject
                val id = case["id"]!!.jsonPrimitive.content
                val label = case["label"]?.jsonPrimitive?.content ?: ""
                val input = RedFlagJson.decodeFromJsonElement(GemmaInput.serializer(), JsonObject(mapOf("signals" to case["signals"]!!, "page" to case["page"]!!)))
                // same as the app: page text normalized before prompting (PromptBuilder.buildObservation does this)
                val user = PromptBuilder.buildUserMessage(PromptBuilder.sanitizeInput(input))
                for (run in 0 until runs) {
                    var plan: GemmaPipeline.FollowUpPlan? = null
                    val rec = runCatching {
                        engine.generate(system, user) { first -> GemmaPipeline.followUpFor(g.bundle, first).also { plan = it }?.prompt }
                    }.fold(
                        onSuccess = { o ->
                            val p = GemmaOutputParser.parse(o.text)
                            val interp = GemmaPipeline.interpret(o.text, o.followupText, plan?.missing ?: emptyList())
                            buildJsonObject {
                                put("id", id); put("run", run); put("label", label)
                                put("latency", o.latencyMs / 1000.0)
                                put("completion_tokens", o.decodeTokens?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("prompt_tokens", o.prefillTokens?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("thought", !o.thought.isNullOrEmpty())
                                put("clean", p.clean)
                                put("parse_error", p.error?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("output", p.obj ?: JsonNull)
                                put("raw", o.text)
                                putJsonArray("schema_errors") { p.schemaErrors.forEach { add(JsonPrimitive(it)) } }
                                put("backend", o.backend); put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("spec_decoding", spec)
                                // v3 pipeline (salvage -> one follow-up -> repair); latency_total includes the follow-up
                                put("latency_total", o.totalLatencyMs / 1000.0)
                                put("followup_prompt", o.followupPrompt?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("followup_raw", o.followupText?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("followup_latency", o.followupLatencyMs?.let { JsonPrimitive(it / 1000.0) } ?: JsonNull)
                                put("pipeline", buildJsonObject {
                                    put("strict_ok", interp.strictOk)
                                    putJsonArray("salvage_notes") { interp.salvageNotes.forEach { add(JsonPrimitive(it)) } }
                                    putJsonArray("missing_before") { interp.missingBefore.forEach { add(JsonPrimitive(it)) } }
                                    putJsonArray("asked") { interp.asked.forEach { add(JsonPrimitive(it)) } }
                                    putJsonArray("missing_after") { interp.missingAfter.forEach { add(JsonPrimitive(it)) } }
                                    putJsonArray("repairs") { interp.repairs.forEach { add(JsonPrimitive(it)) } }
                                    put("core_repaired", interp.coreRepaired)
                                    put("features", interp.features?.let { RedFlagJson.encodeToJsonElement(Features.serializer(), it) } ?: JsonNull)
                                })
                            }.also {
                                log("$id#$run ${o.latencyMs} ms${o.followupLatencyMs?.let { f -> " +followup $f ms" } ?: ""} ${o.backend} " +
                                    "${p.error ?: if (p.features != null) "ok" else "schema"} -> ${if (interp.features != null) "features" else "UNUSABLE"} verdict=${interp.features?.verdict}")
                            }
                        },
                        onFailure = { e ->
                            log("$id#$run ERROR ${e.message}")
                            buildJsonObject { put("id", id); put("run", run); put("label", label); put("error", e.message ?: "error") }
                        },
                    )
                    w.write(rec.toString()); w.newLine(); w.flush()
                }
            }
        }
        log("DONE ${out.absolutePath}")
    }

    companion object { private const val TAG = "RedFlagBench" }
}
