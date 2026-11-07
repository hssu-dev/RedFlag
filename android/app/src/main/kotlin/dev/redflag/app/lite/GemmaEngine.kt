package dev.redflag.app.lite

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import dev.redflag.core.followup.ContextPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.redflag.app.demo.DemoHosts
import dev.redflag.core.model.EngineInfo
import dev.redflag.core.model.Features
import dev.redflag.core.model.RedFlagJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class GemmaOutput(
    val text: String,
    val thought: String?,
    /** first answer only */
    val latencyMs: Long,
    val prefillTokens: Int?,
    val decodeTokens: Int?,
    val backend: String,
    /** "missing keys only" follow-up (asked at most once, same conversation), if any */
    val followupPrompt: String? = null,
    val followupText: String? = null,
    val followupLatencyMs: Long? = null,
) {
    val totalLatencyMs: Long get() = latencyMs + (followupLatencyMs ?: 0)
}

/** Given the first answer, return the follow-up question to ask (missing keys only), or null. */
typealias FollowUp = suspend (firstAnswer: String) -> String?

/** One short follow-up conversation (destination / context turns): text and/or native tool calls. */
data class ChatOutput(val text: String, val toolCalls: List<ContextPolicy.ToolCallIn>, val latencyMs: Long)

interface GemmaEngine {
    val info: EngineInfo
    /** Human label for UI ("GPU", "CPU", "fixtures"). */
    val backendLabel: String
    suspend fun warmUp(): Result<Unit>
    /** hostHint lets the fixture engine answer per demo host; the real engine ignores it. */
    suspend fun generate(system: String, user: String, hostHint: String? = null, followUp: FollowUp? = null): GemmaOutput

    /**
     * A fresh, short conversation (no page text): [tools] are OpenAPI function objects
     * (contracts/context_tools.json); calls are returned, never executed (automatic tool calling off).
     */
    suspend fun chat(system: String, user: String, tools: List<JsonObject> = emptyList(), maxTokens: Int = CHAT_MAX_TOKENS): ChatOutput

    companion object { const val CHAT_MAX_TOKENS = 256 }
}

/**
 * Gemma 4 E4B via LiteRT-LM (same runtime and .litertlm file as last night's Mac experiment).
 * Settings mirror the chosen Mac configuration: free-form JSON (no constrained decoding),
 * thinking off, greedy (top_k=1, temperature 0), max 800 output tokens.
 * GPU first, CPU fallback.
 */
class LiteRtGemmaEngine(
    private val context: Context,
    /** Speculative decoding (model card: ~1.7x decode on S26 GPU). Read per call so the setting applies live. */
    private val speculative: () -> Boolean = { true },
    /** "gpu" | "cpu" | null (= GPU first, CPU fallback). */
    private val forceBackend: String? = null,
) : GemmaEngine {
    private val mutex = Mutex()
    private val inferMutex = Mutex()
    private var engine: Engine? = null
    private var backendName = "none"
    /** Set when GPU initialized but could not run (e.g. emulator: no OpenCL); later inits go straight to CPU. */
    @Volatile private var gpuBroken = false

    override val info = EngineInfo(model = "gemma-4-e4b", runtime = "litert-lm", think = false)
    /** "" until the engine is ready (the badge then shows just "Gemma 4 E4B"). */
    override val backendLabel: String get() = if (backendName == "none") "" else backendName.uppercase()

    fun modelFile(): File = File(modelDir(context), MODEL_FILE)
    fun modelAvailable(): Boolean = modelFile().let { it.exists() && it.length() > 1_000_000_000L }

    override suspend fun warmUp(): Result<Unit> = runCatching { ensureEngine(); Unit }

    private suspend fun ensureEngine(): Engine = mutex.withLock {
        engine?.let { return it }
        withContext(Dispatchers.Default) {
            val path = modelFile().absolutePath
            val attempts = listOf("gpu" to { Backend.GPU() as Backend }, "cpu" to { Backend.CPU() as Backend })
                .filter { (name, _) -> (forceBackend == null || forceBackend == name) && !(name == "gpu" && gpuBroken && forceBackend == null) }
            var last: Throwable? = null
            for ((name, backend) in attempts) {
                var e: Engine? = null
                try {
                    val t0 = System.currentTimeMillis()
                    e = Engine(EngineConfig(modelPath = path, backend = backend(), cacheDir = context.cacheDir.absolutePath))
                    e.initialize()
                    Log.i(TAG, "engine ready on $name in ${System.currentTimeMillis() - t0} ms")
                    backendName = name
                    engine = e
                    return@withContext e
                } catch (c: CancellationException) {
                    runCatching { e?.close() }
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "backend $name failed: ${t.message}")
                    runCatching { e?.close() } // never hold two multi-GB engines at once
                    last = t
                }
            }
            throw IllegalStateException("LiteRT-LM init failed on GPU and CPU", last)
        }
    }

    override suspend fun generate(system: String, user: String, hostHint: String?, followUp: FollowUp?): GemmaOutput {
        if (!modelAvailable()) throw IllegalStateException("model missing: ${modelFile().absolutePath}")
        // One conversation at a time per engine; the engine is only ever closed while holding this lock.
        return inferMutex.withLock {
            val e = ensureEngine()
            try {
                runOnce(e, system, user, followUp)
            } catch (c: CancellationException) {
                throw c // the caller gave up (Go back / late limit): not an engine failure
            } catch (t: Throwable) {
                // GPU can initialize yet fail at the first inference (no OpenCL / driver issue): drop to CPU once.
                if (backendName != "gpu" || forceBackend == "gpu") throw t
                Log.w(TAG, "GPU inference failed (${t.message}); switching to CPU")
                mutex.withLock { gpuBroken = true; runCatching { engine?.close() }; engine = null }
                runOnce(ensureEngine(), system, user, followUp)
            }
        }
    }

    override suspend fun chat(system: String, user: String, tools: List<JsonObject>, maxTokens: Int): ChatOutput {
        if (!modelAvailable()) throw IllegalStateException("model missing: ${modelFile().absolutePath}")
        return inferMutex.withLock { chatOnce(ensureEngine(), system, user, tools, maxTokens) }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun chatOnce(e: Engine, system: String, user: String, tools: List<JsonObject>, maxTokens: Int): ChatOutput {
        val caller = currentCoroutineContext()[Job]
        return withContext(Dispatchers.Default) {
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                tools = tools.map { t -> tool(ContractTool(t.toString())) },
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                automaticToolCalling = false,
                maxOutputToken = maxTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableSpeculativeDecoding = speculative(),
            )
            val t0 = System.nanoTime()
            e.createConversation(config).use { conv ->
                val hook = caller?.invokeOnCompletion { cause -> if (cause is CancellationException) runCatching { conv.cancelProcess() } }
                try {
                    val msg = conv.sendMessage(user)
                    ensureActive()
                    ChatOutput(
                        text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text },
                        toolCalls = msg.toolCalls.map { ContextPolicy.ToolCallIn(it.name, toJsonObject(it.arguments)) },
                        latencyMs = (System.nanoTime() - t0) / 1_000_000,
                    )
                } finally {
                    hook?.dispose()
                }
            }
        }
    }

    /** Declares a contract tool to the runtime; RedFlag never lets the model execute anything. */
    private class ContractTool(private val description: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = description
        override fun execute(paramsJsonString: String): String = "{}"
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun runOnce(e: Engine, system: String, user: String, followUp: FollowUp?): GemmaOutput {
        val caller = currentCoroutineContext()[Job]
        return withContext(Dispatchers.Default) {
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableSpeculativeDecoding = speculative(),
            )
            val t0 = System.nanoTime()
            e.createConversation(config).use { conv ->
                // sendMessage blocks in native code; on cancellation ask the runtime to stop early.
                val hook = caller?.invokeOnCompletion { cause -> if (cause is CancellationException) runCatching { conv.cancelProcess() } }
                try {
                    val msg = conv.sendMessage(user)
                    ensureActive()
                    val latency = (System.nanoTime() - t0) / 1_000_000
                    val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    val bench = runCatching { conv.getBenchmarkInfo() }.getOrNull()
                    // One follow-up at most, in the SAME conversation (the model sees its own first answer;
                    // this is a different question, not a same-input retry).
                    val ask = followUp?.invoke(text)
                    var fuText: String? = null
                    var fuMs: Long? = null
                    if (ask != null) {
                        val t1 = System.nanoTime()
                        val fu = conv.sendMessage(ask)
                        ensureActive()
                        fuMs = (System.nanoTime() - t1) / 1_000_000
                        fuText = fu.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    }
                    GemmaOutput(
                        text = text,
                        thought = msg.channels["thought"],
                        latencyMs = latency,
                        prefillTokens = bench?.lastPrefillTokenCount,
                        decodeTokens = bench?.lastDecodeTokenCount,
                        backend = backendName,
                        followupPrompt = ask,
                        followupText = fuText,
                        followupLatencyMs = fuMs,
                    )
                } finally {
                    hook?.dispose()
                }
            }
        }
    }

    companion object {
        private const val TAG = "RedFlagGemma"

        /** Tool-call arguments (String / Number / Boolean / List / Map from the runtime) -> JSON. */
        fun toJsonObject(m: Map<String, Any?>): JsonObject = JsonObject(m.mapValues { (_, v) -> toJson(v) })
        private fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
            is Iterable<*> -> JsonArray(v.map { toJson(it) })
            is Array<*> -> JsonArray(v.map { toJson(it) })
            else -> JsonPrimitive(v.toString())
        }
        const val MODEL_FILE = "gemma-4-E4B-it.litertlm"
        /** 800 -> 1024 (user decision; real-page answers peaked at 143 tokens, so this is headroom, not a fix). */
        const val MAX_OUTPUT_TOKENS = 1024
        /** /sdcard/Android/data/dev.redflag.app/files/models/ */
        fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), "models")
    }
}

/**
 * Emulator/demo stand-in: returns the demo host's canned features (contracts/demo_hosts.json)
 * serialized as raw model text, so the parser + rule engine path is exercised end to end.
 * Non-demo hosts throw (-> status=error), so the fixture engine can never produce a fake "safe".
 */
class FakeGemmaEngine(
    private val demo: DemoHosts?,
    /** DEBUG knob: a number in files/fake_delay_ms.txt overrides the simulated latency (e.g. 11000 to test late verdicts). */
    private val delayFile: File? = null,
) : GemmaEngine {
    override val info = EngineInfo(model = "fixture", runtime = "fake", think = false)
    override val backendLabel = "FIXTURES"

    override suspend fun warmUp(): Result<Unit> = Result.success(Unit)

    override suspend fun generate(system: String, user: String, hostHint: String?, followUp: FollowUp?): GemmaOutput {
        val ms = delayFile?.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: SIMULATED_MS
        delay(ms)
        // Fixtures exist only for demo hosts. Anything else must NOT get an invented "safe" answer:
        // failing here makes the report status=error -> "couldn't verify" screen.
        val features = hostHint?.let { demo?.match(it)?.host?.fakeFeatures }
            ?: throw IllegalStateException("no fixture for this page (fixture engine only knows demo hosts)")
        val text = RedFlagJson.encodeToString(Features.serializer(), features)
        return GemmaOutput(text, null, ms, null, null, "fixtures")
    }

    /** No fixtures for follow-up turns: callers treat the failure as "no answer" (nothing is added). */
    override suspend fun chat(system: String, user: String, tools: List<JsonObject>, maxTokens: Int): ChatOutput =
        throw IllegalStateException("fixture engine has no follow-up turns")

    companion object {
        const val SIMULATED_MS = 1_400L
        val NEUTRAL = Features(
            evidence = emptyList(), claimedBrand = null, brandMatchesDomain = "no_brand", pageType = "info",
            requestedActions = listOf("none"), urgencyOrThreat = false, tooGoodToBeTrue = false,
            addressesAiAnalyzer = false, verdict = "safe", confidence = "low",
        )
    }
}
