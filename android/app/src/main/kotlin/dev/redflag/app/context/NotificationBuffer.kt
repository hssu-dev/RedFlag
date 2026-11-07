package dev.redflag.app.context

import dev.redflag.core.followup.ConvMessage
import dev.redflag.core.followup.FollowupContracts

/**
 * Recent messenger / SMS / mail notifications, IN MEMORY ONLY (never written to disk, gone when the process
 * dies, entries expire after catalog.limits.notification_ttl_minutes). Read only after the user taps
 * "Allow" on the "May RedFlag check the context?" card (docs/plans/context_analysis.md §5 B).
 */
object NotificationBuffer {
    data class Entry(val pkg: String, val thread: String, val sender: String, val text: String, val time: Long)
    data class Found(val pkg: String, val messages: List<ConvMessage>)

    private val limits get() = FollowupContracts.catalog.limits
    private val entries = ArrayList<Entry>()

    @Synchronized
    fun add(e: Entry, now: Long = System.currentTimeMillis()) {
        if (e.text.isBlank()) return
        if (entries.any { it.pkg == e.pkg && it.thread == e.thread && it.sender == e.sender && it.text == e.text }) return
        entries += e
        trim(now)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size() = entries.size

    private fun trim(now: Long) {
        val ttl = limits.notificationTtlMinutes * 60_000L
        entries.removeAll { now - it.time > ttl }
        while (entries.size > limits.bufferSize) entries.removeAt(0)
    }

    /**
     * The newest message that contains one of [hosts] (the link as shown in the chat), plus up to
     * messages_before earlier messages of the same conversation, oldest first.
     */
    @Synchronized
    fun findLink(hosts: List<String>, now: Long = System.currentTimeMillis()): Found? {
        trim(now)
        val keys = hosts.map { it.lowercase().removePrefix("www.") }.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return null
        val hit = entries.sortedBy { it.time }.lastOrNull { e -> val t = e.text.lowercase(); keys.any { t.contains(it) } } ?: return null
        val thread = entries.filter { it.pkg == hit.pkg && it.thread == hit.thread && it.time <= hit.time && it !== hit }
            .sortedBy { it.time }.takeLast(limits.messagesBefore)
        val msgs = (thread + hit).map { e ->
            ConvMessage(sender = e.sender, text = e.text, hasLink = keys.any { e.text.lowercase().contains(it) })
        }
        return Found(hit.pkg, msgs)
    }

    /** Package of the app whose recent notification carried this link (origin for the Chrome path). */
    fun sourceOf(hosts: List<String>): String? = findLink(hosts)?.pkg
}
