package dev.redflag.app

import dev.redflag.app.link.AllowList
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Skip / open anyway" holds only while the user stays on the site (fix 2026-11-04). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllowListTest {
    @After fun reset() = AllowList.clear()

    @Test
    fun allowedWhileOnTheSiteForgottenAfterLeaving() {
        val t = 1_000_000L
        AllowList.allowHost("phishdemo.builder.example", t)
        AllowList.visited("phishdemo.builder.example", t + 20_000)           // scrolling / url-bar refocus on the same site
        assertTrue(AllowList.isAllowed("phishdemo.builder.example/1/es/index.html", t + 20_000))
        AllowList.visited("google.com", t + 30_000)                          // the user left
        assertFalse(AllowList.isAllowed("phishdemo.builder.example/1/es/index.html", t + 31_000)) // back again: new check
    }

    @Test
    fun freshHandOffSurvivesThePreviousTabFlash() {
        val t = 2_000_000L
        AllowList.allow("https://llm-community.com/post/1312", t)            // RedFlag hands the link to Chrome
        AllowList.visited("news.hada.io", t + 800)                           // Chrome briefly shows the old tab
        assertTrue(AllowList.isAllowed("llm-community.com/post/1312", t + 1_500))
    }

    @Test
    fun stillExpiresAfterTenMinutes() {
        val t = 3_000_000L
        AllowList.allowHost("a.example", t)
        assertFalse(AllowList.isAllowed("a.example", t + 10 * 60 * 1000L + 1))
    }
}
