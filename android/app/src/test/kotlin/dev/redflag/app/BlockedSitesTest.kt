package dev.redflag.app

import dev.redflag.app.data.BlockedSites
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.RiskReport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockedSitesTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private fun fixture(level: String): RiskReport =
        RedFlagJson.decodeFromString(RiskReport.serializer(), File("../../contracts/fixtures/$level.json").readText())

    @Test
    fun rememberedAcrossInstancesAndHostVariants() {
        val now = 1_000_000L
        BlockedSites(ctx).add("phishdemo.builder.example", fixture("critical"), now)
        val again = BlockedSites(ctx) // new process / service restart
        assertNotNull(again.get("phishdemo.builder.example", now + 1000))
        assertNotNull(again.get("PHISHDEMO.builder.example.", now + 1000))
        assertNull(again.get("other.builder.example", now + 1000)) // exact host only: no collateral blocking
        BlockedSites(ctx).add("www.dbs-secure-verify.top", fixture("critical"), now)
        assertNotNull(BlockedSites(ctx).get("dbs-secure-verify.top", now))
    }

    @Test
    fun expiresAfterSevenDays() {
        val now = 5_000_000L
        val b = BlockedSites(ctx)
        b.add("old-phish.top", fixture("danger"), now)
        assertNull(b.get("old-phish.top", now + BlockedSites.TTL_MS + 1))
    }

    @Test
    fun importsOnlyDangerAndCriticalFromRecentChecks() {
        val b = BlockedSites(ctx)
        val now = java.time.Instant.parse("2026-11-05T02:00:00Z").toEpochMilli()
        b.importFrom(listOf(fixture("critical"), fixture("caution"), fixture("safe")), now)
        assertNotNull(b.get(BlockedSites.hostOf(fixture("critical").url), now))
        assertNull(b.get(BlockedSites.hostOf(fixture("caution").url), now))
        assertEquals("critical", b.get(BlockedSites.hostOf(fixture("critical").url), now)!!.level)
    }

    @Test
    fun keepsTheStrictestVerdict() {
        val b = BlockedSites(ctx)
        val now = java.time.Instant.parse("2026-11-05T02:00:00Z").toEpochMilli()
        val crit = fixture("critical"); val dang = fixture("danger").copy(url = crit.url)
        b.importFrom(listOf(dang, crit), now)
        assertEquals("critical", b.get(BlockedSites.hostOf(crit.url), now)!!.level)
        b.add(BlockedSites.hostOf(crit.url), dang, now)
        assertEquals("critical", b.get(BlockedSites.hostOf(crit.url), now)!!.level)
    }

    /** 2026-11-04: "go back" on a caution / couldn't-verify screen must not freeze that verdict for 7 days. */
    @Test
    fun cautionAndUnfinishedChecksAreNotStored() {
        val b = BlockedSites(ctx)
        val now = 9_000_000L
        b.add("llm-community.com", fixture("caution"), now)
        val unfinished = fixture("caution").copy(status = "error", score = 30)
        b.add("slow-news.example", unfinished, now)
        b.add("fine.example", fixture("safe"), now)
        assertNull(b.get("llm-community.com", now))
        assertNull(b.get("slow-news.example", now))
        assertNull(b.get("fine.example", now))
    }

    /** Entries an older build stored (caution 30, analysis_incomplete) are dropped on read: the site is re-analysed. */
    @Test
    fun legacyCautionEntriesAreIgnored() {
        val now = 9_000_000L
        val legacy = mapOf("llm-community.com" to BlockedSites.Entry(now + BlockedSites.TTL_MS, fixture("caution").copy(status = "error")))
        File(ctx.filesDir, "blocked_sites.json").writeText(
            RedFlagJson.encodeToString(kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), BlockedSites.Entry.serializer()), legacy))
        assertNull(BlockedSites(ctx).get("llm-community.com", now))
    }
}
