package dev.redflag.app

import dev.redflag.app.accessibility.ChromeScreenReader
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Chrome's own error page is not the site's page (seen on the S26, 2026-11-05: an unresolvable domain was read
 * from Chrome's screen as 118 chars of "page" and judged safe). The screen reader must recognise it.
 */
class ChromeErrorPageTest {
    @Test fun koreanDnsErrorPage() = assertEquals("ERR_NAME_NOT_RESOLVED", ChromeScreenReader.chromeError(
        "사이트에 연결할 수 없음 redflag-nonexistent-zq7.com의 서버 IP 주소를 찾을 수 없습니다. 다음 방법을 시도해 보세요. 연결 확인 ERR_NAME_NOT_RESOLVED 새로고침 세부정보"))

    @Test fun englishConnectionError() = assertEquals("ERR_CONNECTION_REFUSED", ChromeScreenReader.chromeError(
        "This site can’t be reached localhost refused to connect. ERR_CONNECTION_REFUSED Reload"))

    @Test fun certificateInterstitial() = assertEquals("NET::ERR_CERT_DATE_INVALID", ChromeScreenReader.chromeError(
        "Your connection is not private Attackers might be trying to steal your information NET::ERR_CERT_DATE_INVALID"))

    /** Chrome's Safe Browsing interstitial as read on the S26 (testsafebrowsing.appspot.com, 2026-11-05). */
    private val sbIds = setOf("primary-button", "details-button")
    private val sbText = "위험한 사이트 방문하려고 한 사이트의 공격자가 속임수를 써서 소프트웨어를 설치하거나 비밀번호, 전화번호, 신용카드 번호 등을 공개하도록 유도할 수 있습니다. " +
        "Chrome에서는 안전한 위치로 돌아갈 것을 적극 권장합니다. 이 경고에 대해 자세히 알아보기 안전한 페이지로 돌아가기 세부정보"

    @Test fun safeBrowsingInterstitialIsAHit() =
        assertEquals("safe_browsing_hit:chrome_warning", ChromeScreenReader.chromePage(sbIds, sbText))

    @Test fun errorCodesMapToUnreachableOrSsl() {
        assertEquals("load_error:chrome_ERR_NAME_NOT_RESOLVED", ChromeScreenReader.chromePage(setOf("reload-button", "details-button"), "사이트에 연결할 수 없음 ERR_NAME_NOT_RESOLVED"))
        assertEquals("ssl_error:chrome_NET::ERR_CERT_DATE_INVALID", ChromeScreenReader.chromePage(sbIds, "연결이 비공개로 설정되어 있지 않습니다 NET::ERR_CERT_DATE_INVALID"))
    }

    @Test fun otherInterstitialIsNotTheSite() =
        assertEquals("load_error:chrome_interstitial", ChromeScreenReader.chromePage(sbIds, "연결이 비공개로 설정되어 있지 않습니다 고급"))

    /** The same words on an ordinary page (no Chrome interstitial ids) are the site's own content. */
    @Test fun warningWordsWithoutChromeIdsAreThePage() {
        assertNull(ChromeScreenReader.chromePage(setOf("n", "p"), "위험한 사이트를 구별하는 5가지 방법 — 블로그"))
        assertNull(ChromeScreenReader.chromePage(emptySet(), "Hanaroom Bakery opened on Tiong Bahru Road in September."))
    }

    @Test fun ordinaryPagesAreNotErrors() {
        assertNull(ChromeScreenReader.chromeError("Hanaroom Bakery opened on Tiong Bahru Road in September. Order for pickup"))
        assertNull(ChromeScreenReader.chromeError("ERROR 404 page not found")) // a site's own error page is still read
        assertNull(ChromeScreenReader.chromeError(null))
    }
}
