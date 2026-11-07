package dev.redflag.core.gemma

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger

/**
 * A port of CPython 3.12's json.loads (C scanner, strict=True) so that parse errors carry the
 * exact same `e.msg` text run_eval.py records: "Expecting value", "Expecting property name enclosed
 * in double quotes", "Expecting ':' delimiter", "Expecting ',' delimiter", "Unterminated string
 * starting at", "Invalid control character at", "Invalid \escape", "Invalid \uXXXX escape", "Extra data".
 * Objects keep key insertion order; duplicate keys keep the first position and the last value (dict semantics).
 */
internal object PyJson {
    class DecodeError(val msg: String, val pos: Int) : Exception("$msg: char $pos")

    private class StopIteration(val idx: Int) : Exception(null, null, false, false)

    fun loads(s: String): JsonElement {
        var idx = skipWs(s, 0)
        val (value, end) = try {
            scanOnce(s, idx)
        } catch (e: StopIteration) {
            throw DecodeError("Expecting value", e.idx)
        }
        idx = skipWs(s, end)
        if (idx != s.length) throw DecodeError("Extra data", idx)
        return value
    }

    /** Python json.JSONDecoder().raw_decode(s, idx): decode one value starting exactly at idx. */
    fun rawDecode(s: String, idx: Int): Pair<JsonElement, Int> = try {
        scanOnce(s, idx)
    } catch (e: StopIteration) {
        throw DecodeError("Expecting value", e.idx)
    }

    private fun isWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun skipWs(s: String, start: Int): Int {
        var i = start
        while (i < s.length && isWs(s[i])) i++
        return i
    }

    private fun scanOnce(s: String, idx: Int): Pair<JsonElement, Int> {
        if (idx >= s.length) throw StopIteration(idx)
        return when (val c = s[idx]) {
            '"' -> scanString(s, idx + 1).let { JsonPrimitive(it.first) to it.second }
            '{' -> parseObject(s, idx + 1)
            '[' -> parseArray(s, idx + 1)
            'n' -> if (s.startsWith("null", idx)) JsonNull to idx + 4 else throw StopIteration(idx)
            't' -> if (s.startsWith("true", idx)) JsonPrimitive(true) to idx + 4 else throw StopIteration(idx)
            'f' -> if (s.startsWith("false", idx)) JsonPrimitive(false) to idx + 5 else throw StopIteration(idx)
            'N' -> if (s.startsWith("NaN", idx)) JsonPrimitive(Double.NaN) to idx + 3 else throw StopIteration(idx)
            'I' -> if (s.startsWith("Infinity", idx)) JsonPrimitive(Double.POSITIVE_INFINITY) to idx + 8 else throw StopIteration(idx)
            '-' -> if (s.startsWith("-Infinity", idx)) JsonPrimitive(Double.NEGATIVE_INFINITY) to idx + 9 else matchNumber(s, idx)
            else -> if (c in '0'..'9') matchNumber(s, idx) else throw StopIteration(idx)
        }
    }

    private fun matchNumber(s: String, start: Int): Pair<JsonElement, Int> {
        var i = start
        val n = s.length
        if (i < n && s[i] == '-') {
            i++
            if (i >= n) throw StopIteration(start)
        }
        if (i < n && s[i] in '1'..'9') {
            i++
            while (i < n && s[i] in '0'..'9') i++
        } else if (i < n && s[i] == '0') {
            i++
        } else {
            throw StopIteration(start)
        }
        var isFloat = false
        if (i < n - 1 && s[i] == '.' && s[i + 1] in '0'..'9') {
            isFloat = true
            i += 2
            while (i < n && s[i] in '0'..'9') i++
        }
        if (i < n - 1 && (s[i] == 'e' || s[i] == 'E')) {
            val e = i
            i++
            if (i < n - 1 && (s[i] == '-' || s[i] == '+')) i++
            while (i < n && s[i] in '0'..'9') i++
            if (s[i - 1] in '0'..'9') isFloat = true else i = e
        }
        val text = s.substring(start, i)
        val v: JsonPrimitive = if (isFloat) JsonPrimitive(text.toDouble()) else {
            val bi = BigInteger(text)
            if (bi.bitLength() < 64) JsonPrimitive(bi.toLong()) else JsonPrimitive(bi)
        }
        return v to i
    }

    private fun scanString(s: String, start: Int): Pair<String, Int> {
        val begin = start - 1
        val sb = StringBuilder()
        var i = start
        val n = s.length
        while (true) {
            if (i >= n) throw DecodeError("Unterminated string starting at", begin)
            val c = s[i]
            if (c == '"') return sb.toString() to i + 1
            if (c != '\\') {
                if (c.code < 0x20) throw DecodeError("Invalid control character at", i)
                sb.append(c)
                i++
                continue
            }
            i++
            if (i >= n) throw DecodeError("Unterminated string starting at", begin)
            when (s[i]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000c')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    var cp = hex4(s, i + 1) ?: throw DecodeError("Invalid \\uXXXX escape", i - 1)
                    i += 4
                    if (cp in 0xD800..0xDBFF && i + 2 < n && s[i + 1] == '\\' && s[i + 2] == 'u') {
                        val lo = hex4(s, i + 3) ?: throw DecodeError("Invalid \\uXXXX escape", i + 1)
                        if (lo in 0xDC00..0xDFFF) {
                            cp = 0x10000 + (((cp - 0xD800) shl 10) or (lo - 0xDC00))
                            i += 6
                        }
                    }
                    sb.appendCodePoint(cp)
                }
                else -> throw DecodeError("Invalid \\escape", i - 1)
            }
            i++
        }
    }

    private fun hex4(s: String, at: Int): Int? {
        if (at + 4 > s.length) return null
        var v = 0
        for (k in at until at + 4) {
            val d = Character.digit(s[k], 16)
            if (d < 0 || s[k].code > 0x7f) return null
            v = v * 16 + d
        }
        return v
    }

    private fun parseObject(s: String, start: Int): Pair<JsonElement, Int> {
        val map = LinkedHashMap<String, JsonElement>()
        var i = skipWs(s, start)
        if (i < s.length && s[i] == '}') return JsonObject(map) to i + 1
        while (true) {
            if (i >= s.length || s[i] != '"') throw DecodeError("Expecting property name enclosed in double quotes", i)
            val (key, afterKey) = scanString(s, i + 1)
            i = skipWs(s, afterKey)
            if (i >= s.length || s[i] != ':') throw DecodeError("Expecting ':' delimiter", i)
            i = skipWs(s, i + 1)
            val (value, afterValue) = try {
                scanOnce(s, i)
            } catch (e: StopIteration) {
                throw DecodeError("Expecting value", e.idx)
            }
            map[key] = value
            i = skipWs(s, afterValue)
            if (i < s.length && s[i] == '}') return JsonObject(map) to i + 1
            if (i >= s.length || s[i] != ',') throw DecodeError("Expecting ',' delimiter", i)
            i = skipWs(s, i + 1)
        }
    }

    private fun parseArray(s: String, start: Int): Pair<JsonElement, Int> {
        val list = ArrayList<JsonElement>()
        var i = skipWs(s, start)
        if (i < s.length && s[i] == ']') return JsonArray(list) to i + 1
        while (true) {
            val (value, after) = try {
                scanOnce(s, i)
            } catch (e: StopIteration) {
                throw DecodeError("Expecting value", e.idx)
            }
            list += value
            i = skipWs(s, after)
            if (i < s.length && s[i] == ']') return JsonArray(list) to i + 1
            if (i >= s.length || s[i] != ',') throw DecodeError("Expecting ',' delimiter", i)
            i = skipWs(s, i + 1)
        }
    }
}
