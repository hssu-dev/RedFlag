package dev.redflag.core.gemma

import dev.redflag.core.ContractBundle
import dev.redflag.core.model.Features
import dev.redflag.core.util.TextNorm
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Twin of backend/redflag_backend/gemma_repair.py (v3 section). Order of use (LiteEngine):
 *  1. strict parse (run_eval.py parity)            -> valid? done
 *  2. salvage(raw): read every `"key": <value>`, canonicalize misspelled keys (lossless)
 *  3. still missing keys -> ONE follow-up question for exactly those keys (followupPrompt)
 *  4. still missing -> FeatureRepair defaults (features_repaired; core_fields_repaired if a core key)
 *  5. enforceEvidence (<= 3 snippets, <= 15 words each) on the final features
 * eval/real/FAILURES.md: the dominant real-page failure is a misspelled key (addresses_ai_ai_analyzer,
 * too_good_to_be_be_true, urgency_orthreat ...), which steps 2 fixes without asking the model again.
 */
object GemmaSalvage {
    val KEYS = GemmaOutputParser.KEYS
    val CORE_KEYS = setOf("claimed_brand", "brand_matches_domain", "requested_actions")
    const val EVIDENCE_MAX_ITEMS = 3
    const val EVIDENCE_MAX_WORDS = 15
    private val KEY_RE = Regex("\"([A-Za-z_]{3,40})\"\\s*:")
    private val FENCE = Regex("```(?:json)?")

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun canonicalKey(k: String): String? {
        if (k in KEYS) return k
        val toks = k.lowercase().split('_').filter { it.isNotEmpty() }
        val dedup = toks.filterIndexed { i, t -> i == 0 || t != toks[i - 1] }
        val cand = dedup.joinToString("_")
        if (cand in KEYS) return cand
        val flat = k.lowercase().filter { it in 'a'..'z' }
        val scored = KEYS.map { lev(flat, it.replace("_", "")) to it }.sortedWith(compareBy({ it.first }, { it.second }))
        return if (scored[0].first <= 2 && scored[1].first > scored[0].first) scored[0].second else null
    }

    data class Salvaged(val obj: JsonObject?, val notes: List<String>)

    fun salvage(raw: String): Salvaged {
        var text = raw
        val ch = text.lastIndexOf("<channel|>")
        if (ch >= 0) text = text.substring(ch + "<channel|>".length)
        text = FENCE.replace(text, "")
        val obj = LinkedHashMap<String, JsonElement>()
        val notes = ArrayList<String>()
        for (m in KEY_RE.findAll(text)) {
            val key = m.groupValues[1]
            val ck = canonicalKey(key) ?: continue
            if (ck in obj) continue
            var i = m.range.last + 1
            while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i++
            val value = try { PyJson.rawDecode(text, i).first } catch (_: Exception) { continue }
            obj[ck] = value
            if (ck != key) notes += "key:$key->$ck"
        }
        return Salvaged(if (obj.isEmpty()) null else JsonObject(obj), notes)
    }

    fun missingKeys(obj: JsonObject?): List<String> = KEYS.filter { obj == null || it !in obj }

    fun followupPrompt(bundle: ContractBundle, missing: List<String>): String =
        bundle.followupTemplate.replace("{keys}", KEYS.filter { it in missing }.joinToString(", "))

    /** Merge follow-up answer keys into the first answer: only the asked-for keys, first answer wins otherwise. */
    fun merge(first: JsonObject?, followup: JsonObject?, asked: List<String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        first?.forEach { (k, v) -> out[k] = v }
        followup?.forEach { (k, v) -> if (k in asked && k !in out) out[k] = v }
        return JsonObject(out)
    }

    fun isCoreRepair(repairs: List<String>): Boolean = repairs.any { it.substringBefore("->") in CORE_KEYS }

    fun enforceEvidence(f: Features): Features =
        f.copy(evidence = f.evidence.map { TextNorm.pyCollapse(it).split(' ').filter { w -> w.isNotEmpty() }.take(EVIDENCE_MAX_WORDS).joinToString(" ") }
            .take(EVIDENCE_MAX_ITEMS))
}
