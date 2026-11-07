package dev.redflag.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.math.BigDecimal

object TestSupport {
    val repoRoot: File by lazy {
        System.getProperty("redflag.repoRoot")?.let(::File) ?: File("../..").canonicalFile
    }

    fun repoFile(path: String) = File(repoRoot, path)

    fun contractJson(name: String): JsonObject =
        Json.parseToJsonElement(repoFile("contracts/$name").readText()) as JsonObject

    /** Structural JSON equality incl. object key order; numbers compared numerically. */
    fun jsonTreeEquals(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == null || b == null -> a == b
        a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
        a is JsonObject && b is JsonObject ->
            a.keys.toList() == b.keys.toList() && a.keys.all { jsonTreeEquals(a[it], b[it]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonTreeEquals(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> when {
            a.isString || b.isString -> a.isString == b.isString && a.content == b.content
            a.content == b.content -> true
            else -> runCatching { BigDecimal(a.content).compareTo(BigDecimal(b.content)) == 0 }.getOrDefault(false)
        }
        else -> false
    }
}
