package dev.redflag.app.link

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.redflag.app.graph

/**
 * Hands a URL to Chrome. setPackage() prevents the VIEW intent from looping back to us (we are the
 * default browser). Without Chrome, a chooser that excludes RedFlag is shown.
 */
object ChromeLauncher {
    const val CHROME = "com.android.chrome"

    /** Open in Chrome, still checked: RedFlag's cover (accessibility) analyses it there via Chrome's screen. */
    fun openUnderCover(context: Context, url: String, host: String, reason: String) {
        ScreenHandoff.request(host, reason)
        launch(context, context.graph.demoHosts?.match(url)?.localUrl ?: url)
    }

    fun open(context: Context, url: String) {
        val target = context.graph.demoHosts?.match(url)?.localUrl ?: url // DEMO: fake domains never resolve
        AllowList.allow(target)
        AllowList.allow(url)
        launch(context, target)
    }

    private fun launch(context: Context, target: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent(view).setPackage(CHROME))
        } catch (_: ActivityNotFoundException) {
            val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, LinkHandlerActivity::class.java)))
            context.startActivity(chooser)
        }
    }
}

/**
 * A link the hidden WebView could not read (CDN check / block / cloaking) is opened in Chrome UNDER RedFlag's
 * cover, and the cover reads Chrome's screen instead of loading the page a second time (user decision 2026-11-04).
 */
object ScreenHandoff {
    private const val TTL_MS = 30_000L
    /** host -> (deadline, why the hidden browser failed, e.g. "page_state:blocked") */
    private val pending = HashMap<String, Pair<Long, String>>()
    @Synchronized fun request(host: String, reason: String, now: Long = System.currentTimeMillis()) {
        pending[host.lowercase().trimEnd('.')] = (now + TTL_MS) to reason
    }
    /** The hidden browser's failure for [host] (once, within 30 s of [request]), else null. */
    @Synchronized fun take(host: String?, now: Long = System.currentTimeMillis()): String? {
        val k = host?.lowercase()?.trimEnd('.') ?: return null
        val (t, why) = pending.remove(k) ?: return null
        return why.takeIf { t > now }
    }
}

/**
 * Hosts the user (or a Lite "safe" verdict) just let through, so the Chrome watcher does not
 * cover the same page again. Short-lived and in-memory only.
 */
object AllowList {
    private const val TTL_MS = 10 * 60 * 1000L
    /** Chrome may still show the previous tab's address for a moment after a hand-off: keep fresh entries. */
    const val GRACE_MS = 5_000L
    private class Entry(val sinceMs: Long, val untilMs: Long)
    private val entries = HashMap<String, Entry>()

    private fun key(url: String): String? = runCatching { Uri.parse(if ("://" in url) url else "https://$url").host?.lowercase()?.trimEnd('.') }.getOrNull()

    private fun put(k: String, now: Long) { entries[k] = Entry(now, now + TTL_MS) }

    @Synchronized fun allow(url: String, now: Long = System.currentTimeMillis()) { key(url)?.let { put(it, now) } }
    @Synchronized fun allowHost(host: String, now: Long = System.currentTimeMillis()) { put(host.lowercase().trimEnd('.'), now) }
    @Synchronized fun isAllowed(url: String, now: Long = System.currentTimeMillis()): Boolean =
        key(url)?.let { (entries[it]?.untilMs ?: 0) > now } ?: false

    /**
     * Chrome now shows [host]: every OTHER allowed host is forgotten (unless allowed in the last [GRACE_MS]).
     * "Skip / open anyway / continue" holds only while the user stays on that site; leaving and coming back
     * means a new check (fix 2026-11-04: a skipped phishing page stayed silent for 10 minutes on every revisit).
     */
    @Synchronized fun visited(host: String, now: Long = System.currentTimeMillis()) {
        val h = host.lowercase().trimEnd('.')
        entries.entries.removeAll { (k, e) -> k != h && now - e.sinceMs > GRACE_MS }
    }

    @Synchronized internal fun clear() = entries.clear()
}
