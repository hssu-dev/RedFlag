package dev.redflag.app

import dev.redflag.app.context.NotificationBuffer
import dev.redflag.app.context.NotificationBuffer.Entry
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Memory-only context buffer: link match, thread window, TTL, size cap. */
class NotificationBufferTest {
    private val sms = "com.google.android.apps.messaging"
    private val t0 = 1_000_000_000L

    @Before fun reset() = NotificationBuffer.clear()

    @Test
    fun findsLinkMessageWithUpToThreeEarlierMessagesOfTheSameThread() {
        val th = "010-1234-5678"
        listOf("안녕하세요", "택배 관련 안내입니다", "주소가 확인되지 않습니다", "보류중", "확인: https://Redelivery-Center.com/a").forEachIndexed { i, t ->
            NotificationBuffer.add(Entry(sms, th, th, t, t0 + i * 1000), now = t0 + 10_000)
        }
        NotificationBuffer.add(Entry(sms, "엄마", "엄마", "저녁 먹자", t0 + 6000), now = t0 + 10_000)
        val f = NotificationBuffer.findLink(listOf("redelivery-center.com"), now = t0 + 10_000)
        assertNotNull(f)
        assertEquals(sms, f.pkg)
        assertEquals(listOf("택배 관련 안내입니다", "주소가 확인되지 않습니다", "보류중", "확인: https://Redelivery-Center.com/a"), f.messages.map { it.text })
        assertTrue(f.messages.last().hasLink)
        assertTrue(f.messages.dropLast(1).none { it.hasLink })
    }

    @Test
    fun wwwPrefixAndDuplicatesAndNoMatch() {
        NotificationBuffer.add(Entry(sms, "a", "a", "go www.safepay-escrow.shop/x", t0), now = t0)
        NotificationBuffer.add(Entry(sms, "a", "a", "go www.safepay-escrow.shop/x", t0 + 5), now = t0) // same message again
        assertEquals(1, NotificationBuffer.size())
        assertNotNull(NotificationBuffer.findLink(listOf("www.safepay-escrow.shop"), now = t0))
        assertNull(NotificationBuffer.findLink(listOf("other.com"), now = t0))
        assertNull(NotificationBuffer.findLink(emptyList(), now = t0))
    }

    @Test
    fun expiresAfterTtlAndCapsSize() {
        NotificationBuffer.add(Entry(sms, "a", "a", "old https://x-site.com", t0), now = t0)
        assertNull(NotificationBuffer.findLink(listOf("x-site.com"), now = t0 + 31 * 60_000L)) // TTL 30 min
        repeat(80) { NotificationBuffer.add(Entry(sms, "b", "b", "m$it", t0 + it), now = t0 + it) }
        assertEquals(60, NotificationBuffer.size()) // buffer_size
    }
}
