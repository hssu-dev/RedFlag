package dev.redflag.core.gate

import dev.redflag.core.Contracts
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface KnownGoodSet {
    fun contains(registrableDomain: String): Boolean
}

/**
 * Tranco top-100k registrable domains as a sorted LongArray of 64-bit hashes
 * (first 8 bytes, big-endian, of SHA-256(lowercase ASCII domain)), sorted as signed longs; binary search.
 * File format (known_good/tranco_top100k.bin): N x int64 big-endian, no header.
 */
class HashedKnownGoodSet(private val hashes: LongArray) : KnownGoodSet {
    val size: Int get() = hashes.size

    override fun contains(registrableDomain: String): Boolean =
        java.util.Arrays.binarySearch(hashes, hash(registrableDomain)) >= 0

    companion object {
        const val RESOURCE = "known_good/tranco_top100k.bin"

        fun hash(domain: String): Long {
            val d = MessageDigest.getInstance("SHA-256").digest(domain.lowercase().toByteArray(Charsets.US_ASCII))
            return ByteBuffer.wrap(d, 0, 8).long
        }

        fun fromBytes(bytes: ByteArray): HashedKnownGoodSet {
            require(bytes.size % 8 == 0) { "corrupt known-good set (${bytes.size} bytes)" }
            val buf = ByteBuffer.wrap(bytes)
            val arr = LongArray(bytes.size / 8) { buf.long }
            for (i in 1 until arr.size) require(arr[i - 1] < arr[i]) { "known-good set not sorted/unique at $i" }
            return HashedKnownGoodSet(arr)
        }

        /** Loads the bundled set from the classpath; throws if it is not packaged. */
        fun fromClasspath(path: String = RESOURCE): HashedKnownGoodSet =
            fromBytes(Contracts.classpathBytes(path) ?: error("missing resource $path"))

        fun toBytes(sortedHashes: LongArray): ByteArray {
            val buf = ByteBuffer.allocate(sortedHashes.size * 8)
            sortedHashes.forEach { buf.putLong(it) }
            return buf.array()
        }
    }
}

class InMemoryKnownGoodSet(domains: Collection<String>) : KnownGoodSet {
    private val set = domains.map { it.lowercase() }.toSet()
    override fun contains(registrableDomain: String): Boolean = registrableDomain.lowercase() in set
}

interface SafeVerdictCache {
    fun isSafe(registrableDomain: String, nowMs: Long): Boolean
    fun putSafe(registrableDomain: String, ttlMs: Long, nowMs: Long)
}

class InMemorySafeVerdictCache : SafeVerdictCache {
    private val expiry = ConcurrentHashMap<String, Long>()

    override fun isSafe(registrableDomain: String, nowMs: Long): Boolean {
        val key = registrableDomain.lowercase()
        val until = expiry[key] ?: return false
        if (nowMs >= until) {
            expiry.remove(key, until)
            return false
        }
        return true
    }

    override fun putSafe(registrableDomain: String, ttlMs: Long, nowMs: Long) {
        expiry[registrableDomain.lowercase()] = nowMs + ttlMs
    }

    fun clear() = expiry.clear()
}
