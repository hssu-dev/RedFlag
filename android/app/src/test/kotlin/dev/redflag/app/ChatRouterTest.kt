package dev.redflag.app

import dev.redflag.app.chat.ChatRouter
import dev.redflag.app.chat.ChatRouter.Route
import dev.redflag.core.Contracts
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-11-05-chat-home-design.md §2 */
class ChatRouterTest {
    private val bundle = Contracts.load()
    private fun route(t: String, hasCheck: Boolean = false) = ChatRouter.route(t, bundle, hasCheck)

    @Test fun aBareLinkIsChecked() {
        val r = assertIs<Route.Check>(route("https://safepay-escrow.shop/pay"))
        assertEquals("https://safepay-escrow.shop/pay", r.url)
        assertNull(r.message)
    }

    /** The text around the link is a RECEIVED message (untrusted), even after an earlier check. */
    @Test fun textWithALinkIsAReceivedMessage() {
        val r = assertIs<Route.Check>(route("[택배] 주소 불명으로 보류. 확인: https://cjlogis-kr.co/track 오늘까지", hasCheck = true))
        assertEquals("https://cjlogis-kr.co/track", r.url)
        assertTrue(r.message!!.contains("주소 불명"), r.message)
    }

    @Test fun severalLinksAskWhichOne() {
        val r = assertIs<Route.Pick>(route("공식 https://www.cjlogistics.com 말고 여기 https://cjlogis-kr.co/track 로"))
        assertEquals(2, r.links.size)
    }

    @Test fun defangedLinkIsFound() { assertIs<Route.Check>(route("hxxps://evil-login[.]com/a")) }

    /** No link after a check: the user's own account of the situation (user_statement). */
    @Test fun textWithoutALinkAfterACheckIsAStatement() =
        assertEquals(Route.Statement("모르는 번호로 문자가 왔어요"), route("  모르는 번호로 문자가 왔어요 ", hasCheck = true))

    @Test fun textWithoutALinkBeforeAnyCheckFindsNothing() = assertEquals(Route.NoLink, route("이거 사기인가요?"))

    @Test fun blankIsIgnored() = assertEquals(Route.Empty, route("   \n "))
}
