package dev.redflag.core.tools

import dev.redflag.core.Contracts
import dev.redflag.core.gate.HashedKnownGoodSet
import dev.redflag.core.gate.PublicSuffixList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.IDN
import java.util.zip.ZipFile

/**
 * Builds known_good/tranco_top100k.bin (+ tranco_meta.json) from a Tranco CSV ("rank,domain"; a .zip
 * containing it is also accepted). Every domain is mapped through the bundled PSL (private section
 * included): entries that are public suffixes themselves / have no registrable domain are dropped,
 * the rest are reduced to eTLD+1 and de-duplicated.
 *
 * args: <csv|zip> <listId> <listDate> <outDir> [topN=100000]
 */
fun main(args: Array<String>) {
    require(args.size >= 4 && args[0].isNotEmpty()) {
        "usage: BuildTranco <tranco.csv|zip> <listId> <listDate> <outDir> [topN]   (see tools/build_tranco.sh)"
    }
    val input = File(args[0])
    val listId = args[1]
    val listDate = args[2]
    val outDir = File(args[3])
    val topN = args.getOrNull(4)?.toIntOrNull() ?: 100_000

    val psl = Contracts.load().psl
    val lines: Sequence<String>
    val closer: AutoCloseable
    if (input.name.endsWith(".zip")) {
        val zip = ZipFile(input)
        val entry = zip.entries().asSequence().first { it.name.endsWith(".csv") }
        val reader = zip.getInputStream(entry).bufferedReader()
        lines = reader.lineSequence()
        closer = AutoCloseable { reader.close(); zip.close() }
    } else {
        val reader = input.bufferedReader()
        lines = reader.lineSequence()
        closer = reader
    }
    val result = closer.use { buildTrancoSet(lines, topN, psl) }

    outDir.mkdirs()
    File(outDir, "tranco_top100k.bin").writeBytes(HashedKnownGoodSet.toBytes(result.hashes))
    val meta = buildJsonObject {
        put("source", "https://tranco-list.eu/list/$listId")
        put("list_id", listId)
        put("list_date", listDate)
        put("top_n", topN)
        put("rows_read", result.rowsRead)
        put("dropped_public_suffix_or_invalid", result.dropped)
        put("domains_written", result.hashes.size)
        put("psl_version", pslVersion())
        put("format", "N x int64 big-endian, sorted ascending (signed), = first 8 bytes of SHA-256(lowercase ASCII eTLD+1)")
    }
    File(outDir, "tranco_meta.json").writeText(
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), meta) + "\n",
    )
    println("tranco $listId ($listDate): rows=${result.rowsRead} dropped=${result.dropped} written=${result.hashes.size} -> $outDir")
}

class TrancoBuild(val hashes: LongArray, val rowsRead: Int, val dropped: Int)

fun buildTrancoSet(lines: Sequence<String>, topN: Int, psl: PublicSuffixList): TrancoBuild {
    var rows = 0
    var dropped = 0
    val domains = LinkedHashSet<String>()
    for (line in lines) {
        if (rows >= topN) break
        val t = line.trim()
        if (t.isEmpty()) continue
        rows++
        val raw = t.substringAfter(',').trim().lowercase().trimEnd('.')
        val ascii = if (raw.all { it.code < 0x80 }) raw else runCatching { IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()
        val reg = ascii?.let { psl.registrableDomain(it) }
        if (reg == null) { dropped++; continue }
        domains += reg
    }
    val hashes = domains.map { HashedKnownGoodSet.hash(it) }.distinct().sorted().toLongArray()
    return TrancoBuild(hashes, rows, dropped)
}

private fun pslVersion(): String =
    Contracts.classpathText("contracts/public_suffix_list.dat")?.lineSequence()
        ?.firstOrNull { it.contains("VERSION:") }?.substringAfter("VERSION:")?.trim() ?: "unknown"
