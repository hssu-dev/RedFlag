package dev.redflag.app.check

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.app.ShareCompat

/**
 * Exported entry for "Share -> Check with RedFlag" (ACTION_SEND text/plain) and the text-selection menu
 * (ACTION_PROCESS_TEXT). UI-less: it reads ONLY the shared text (and which app shared it), puts them in the
 * in-process SharedInbox and opens the chat home without extras, so another app cannot inject anything else
 * (a URL to pick, a statement, a report id, a fake sending app).
 */
class ShareEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = sharedText(intent)
        if (!text.isNullOrBlank()) {
            // into the chat home: the text and the sending app go through the in-process inbox; the (exported)
            // home is opened with no extras at all (docs/superpowers/specs/2026-11-05-chat-home-design.md §4)
            dev.redflag.app.chat.SharedInbox.put(text.take(dev.redflag.core.gate.UrlExtractor.MAX_INPUT), callerPackage())
            startActivity(Intent(this, dev.redflag.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }

    /** Sending app: ShareCompat's extra when the sender set it, else the referrer (category only is used). */
    private fun callerPackage(): String? =
        runCatching { ShareCompat.getCallingPackage(this) }.getOrNull()
            ?: referrer?.takeIf { it.scheme == "android-app" }?.host?.takeIf { it != packageName && it != "android" }

    companion object {
        fun sharedText(intent: Intent?): String? = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { s -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.contains(s) != true },
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            ).joinToString("\n").ifBlank { null }
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
    }
}
