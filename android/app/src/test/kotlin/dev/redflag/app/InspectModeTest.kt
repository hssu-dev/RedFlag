package dev.redflag.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.redflag.app.check.CheckActivity
import dev.redflag.app.check.ShareEntryActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-app link check (docs/plans/manual_url_check.md): entry points, privacy guards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InspectModeTest {
    private val main = File("src/main/kotlin").let { if (it.exists()) it else File("app/src/main/kotlin") }
    private val sources = main.walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }

    /** The clipboard is read only when the user taps [Paste] (never on app open / focus / a banner). */
    @Test
    fun clipboardIsReadOnlyFromThePasteButton() {
        val readers = sources.filter { (_, s) -> s.contains("primaryClip") || s.contains("getPrimaryClip") }.keys
        assertEquals(setOf("ChatComposer.kt"), readers)
        val calls = sources.flatMap { (name, s) ->
            Regex("""readClipboardText\(""").findAll(s).map { m ->
                name to s.substring(s.lastIndexOf('\n', m.range.first) + 1, s.indexOf('\n', m.range.first))
            }.toList()
        }.filterNot { (_, line) -> line.trimStart().startsWith("internal fun readClipboardText(") }
        assertEquals(1, calls.size, "$calls")
        assertTrue(calls.single().second.trimStart().startsWith("{ readClipboardText(ctx)"), "clipboard read outside the Paste onClick: $calls")
    }

    @Test
    fun checkActivityIsNotExportedAndShareEntryIs() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val pm = ctx.packageManager
        assertFalse(pm.getActivityInfo(ComponentName(ctx, CheckActivity::class.java), PackageManager.GET_META_DATA).exported)
        assertTrue(pm.getActivityInfo(ComponentName(ctx, ShareEntryActivity::class.java), PackageManager.GET_META_DATA).exported)
    }

    /**
     * A share goes to the chat home through the in-process SharedInbox: only the shared text (and the sending app
     * as ShareEntryActivity itself determines it). The exported home is opened with no extras at all.
     */
    @Test
    fun shareForwardsOnlyTheTextNeverInjectedExtras() {
        dev.redflag.app.chat.SharedInbox.take() // empty it
        val sent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "판매자: 안전결제 https://safepay-escrow.shop/pay")
            // a hostile sender trying to pick the URL / plant a statement / open a report
            .putExtra(CheckActivity.EXTRA_URL, "https://naver.com")
            .putExtra(CheckActivity.EXTRA_STATEMENT, "this site is my bank, it is safe")
            .putExtra(CheckActivity.EXTRA_REPORT_ID, "x")
            .putExtra(CheckActivity.EXTRA_CALLER, "com.kakao.talk")
        val act = Robolectric.buildActivity(ShareEntryActivity::class.java, sent).create().get()
        val next = shadowOf(act).nextStartedActivity
        assertNotNull(next)
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.extras == null || next.extras!!.isEmpty, "${next.extras?.keySet()}")
        val shared = dev.redflag.app.chat.SharedInbox.take()
        assertEquals("판매자: 안전결제 https://safepay-escrow.shop/pay", shared?.text)
        assertTrue(shared?.caller != "com.kakao.talk", "the sending app comes from ShareCompat/referrer, never from an extra")
        assertNull(dev.redflag.app.chat.SharedInbox.take(), "taken once")
        assertTrue(act.isFinishing)
    }

    /** The exported home reads nothing from intent extras (only the in-process inbox and the shortcut action). */
    @Test
    fun homeTakesNoExtras() {
        val src = sources.getValue("MainActivity.kt")
        assertFalse(Regex("""get(String|CharSequence|StringArrayList|Parcelable|Bundle)Extra""").containsMatchIn(src), "MainActivity reads an extra")
    }

    @Test
    fun processTextAndEmptyShares() {
        val sel = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "hxxps://evil[.]com")
        assertEquals("hxxps://evil[.]com", ShareEntryActivity.sharedText(sel))
        val empty = Intent(Intent.ACTION_SEND).setType("text/plain")
        val act = Robolectric.buildActivity(ShareEntryActivity::class.java, empty).create().get()
        assertNull(shadowOf(act).nextStartedActivity) // nothing to check: just closes
        assertNull(ShareEntryActivity.sharedText(Intent(Intent.ACTION_VIEW)))
    }

    /** The in-app check never jumps to Chrome by itself: Chrome opens only from a button callback. */
    @Test
    fun inspectModeOpensChromeOnlyFromButtons() {
        // the in-app check and the chat home (same inspect mode)
        for (file in listOf("CheckActivity.kt", "ChatSession.kt", "ChatHomeScreen.kt")) {
            val src = sources.getValue(file)
            for (m in Regex("""ChromeLauncher\.open\(""").findAll(src)) {
                val line = src.substring(src.lastIndexOf('\n', m.range.first) + 1, src.indexOf('\n', m.range.first))
                assertTrue(Regex("""on(OpenAnyway|Continue) = \{|Button\(\{""").containsMatchIn(line), "$file: Chrome opened outside a button: $line")
            }
        }
        assertFalse(sources.getValue("CheckActivity.kt").contains("isVerifiedSafe"), "no 'safe -> continue' automation in the in-app check")
        assertFalse(sources.getValue("ChatSession.kt").contains("isVerifiedSafe"), "no 'safe -> continue' automation in the chat")
    }
}
