package dev.redflag.app.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Follows a short link's redirects WITHOUT rendering anything (HEAD, then GET if HEAD is refused),
 * max 5 hops, so the gate can judge the real destination. Only the shortener services are contacted.
 */
class ShortLinkResolver(private val http: OkHttpClient) {
    suspend fun resolve(url: String, maxHops: Int = 5): String? = withContext(Dispatchers.IO) {
        var current = url
        repeat(maxHops) {
            val next = runCatching { hop(current, "HEAD") ?: hop(current, "GET") }.getOrNull() ?: return@withContext current.takeIf { it != url }
            current = next
        }
        current
    }

    private fun hop(url: String, method: String): String? {
        val req = Request.Builder().url(url).method(method, null)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) RedFlag-LinkCheck").build()
        http.newCall(req).execute().use { r ->
            if (r.code in 300..399) return r.header("Location")?.let { r.request.url.resolve(it)?.toString() }
            return null
        }
    }
}
