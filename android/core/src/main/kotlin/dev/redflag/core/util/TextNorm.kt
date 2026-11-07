package dev.redflag.core.util

import java.text.Normalizer

/**
 * Twin of backend/redflag_backend/textnorm.py (see scoring_rules.json sensitive_fields.doc):
 * NFKC, lowercase, every code point that is not a letter or digit -> space, collapse spaces, trim.
 */
object TextNorm {
    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val lowered = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(lowered.length)
        var i = 0
        while (i < lowered.length) {
            val cp = lowered.codePointAt(i)
            if (Character.isLetter(cp) || Character.isDigit(cp)) sb.appendCodePoint(cp) else sb.append(' ')
            i += Character.charCount(cp)
        }
        return sb.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Whole-token containment of normalize(phrase) in an already-normalized haystack. */
    fun containsPhrase(haystackNorm: String, phrase: String): Boolean {
        val p = normalize(phrase)
        if (p.isEmpty()) return false
        return " $haystackNorm ".contains(" $p ")
    }

    /** Python str.isspace() approximation (Unicode White_Space + ASCII separators). */
    fun isPySpace(c: Char): Boolean =
        Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085' || c in '\u001c'..'\u001f'

    /** Python str.strip() with no arguments. */
    fun pyStrip(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isPySpace(s[a])) a++
        while (b > a && isPySpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** Python s[:n] (counts code points, not UTF-16 units). */
    fun takeCodePoints(s: String, n: Int): String {
        if (s.length <= n) return s
        val end = runCatching { s.offsetByCodePoints(0, n) }.getOrElse { return s }
        return s.substring(0, end)
    }

    /** Python " ".join(s.split()) — whitespace runs -> one space, trimmed. */
    fun pyCollapse(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (isPySpace(c)) { pendingSpace = sb.isNotEmpty(); continue }
            if (pendingSpace) { sb.append(' '); pendingSpace = false }
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Twin of backend/redflag_backend/prompt_text.py sanitize_page_text: control chars / line breaks -> space,
     * '"' -> '\'', '\\' -> '/', whitespace runs -> one space. Page text goes into the prompt in this form so the
     * model never has to escape anything it copies into evidence.
     */
    fun sanitizeForPrompt(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when {
                    c.code < 32 || c.code == 127 || c == '\u2028' || c == '\u2029' || c == '\u0085' -> ' '
                    c == '"' -> '\''
                    c == '\\' -> '/'
                    else -> c
                },
            )
        }
        return pyCollapse(sb.toString())
    }

    /** scoring_rules.json hidden_text_patterns: NFKC, lowercase, whitespace runs -> one space, trim. */
    fun normalizeForPatterns(text: String?): String =
        if (text.isNullOrEmpty()) "" else pyCollapse(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase())
}
