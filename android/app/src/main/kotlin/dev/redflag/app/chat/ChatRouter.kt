package dev.redflag.app.chat

import dev.redflag.core.ContractBundle
import dev.redflag.core.gate.UrlExtractor

/**
 * What one message typed into the chat home means (docs/superpowers/specs/2026-11-05-chat-home-design.md §2).
 * Pure. The split matters for prompt injection: text sent WITH a link is a received message (untrusted,
 * `<untrusted_conversation>`); text sent WITHOUT one, after a check, is the user's own account (`<user_statement>`).
 */
object ChatRouter {
    sealed interface Route {
        /** One link: check it. [message] = the rest of the text (a received message), if any. */
        data class Check(val url: String, val message: String?) : Route
        /** Several links: ask which one. */
        data class Pick(val links: List<String>, val message: String?) : Route
        /** No link, and there is a check to add it to: the user explains their situation. */
        data class Statement(val text: String) : Route
        /** No link and nothing to add it to. */
        data object NoLink : Route
        data object Empty : Route
    }

    fun route(text: String, bundle: ContractBundle, hasCheck: Boolean): Route {
        val t = text.trim()
        if (t.isEmpty()) return Route.Empty
        val ex = UrlExtractor.extract(t.take(UrlExtractor.MAX_INPUT), bundle)
        return when {
            ex.urls.size == 1 -> Route.Check(ex.urls.single(), ex.message)
            ex.urls.size > 1 -> Route.Pick(ex.urls, ex.message)
            hasCheck -> Route.Statement(t)
            else -> Route.NoLink
        }
    }
}
