package dev.redflag.app

import dev.redflag.app.demo.DemoHosts
import dev.redflag.core.model.RedFlagJson
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Demo mapping must work for full URLs, fake domains, and Chrome's scheme-less address bar text. */
class DemoHostsTest {
    private val doc = RedFlagJson.decodeFromString(DemoHosts.Doc.serializer(), File("../../contracts/demo_hosts.json").readText())
    private val demo = DemoHosts(doc, doc.baseUrls.getValue("emulator"))

    @Test fun fakeDomainUrl() {
        val m = demo.match("https://dbs-secure-verify.top/login?ref=sms")!!
        assertEquals("dbs-secure-verify.top", m.fakeHost)
        assertEquals("http://10.0.2.2:8081/dbs/", m.localUrl)
        assertEquals("https://dbs-secure-verify.top/login?ref=sms", m.fakeUrl)
    }

    @Test fun subdomainOfFakeDomain() = assertEquals("cjlogis-kr.co", demo.match("https://www.cjlogis-kr.co/x")!!.fakeHost)

    @Test fun localUrlWithScheme() = assertEquals("cjlogis-kr.co", demo.match("http://10.0.2.2:8081/cj/")!!.fakeHost)

    @Test fun localUrlFromChromeAddressBarWithoutScheme() {
        val m = demo.match("10.0.2.2:8081/bakery/")!!
        assertEquals("hanaroombakery.sg", m.fakeHost)
        assertEquals("https://hanaroombakery.sg/", m.fakeUrl)
    }

    @Test fun schemelessFakeDomain() = assertEquals("https://soomstudio.kr", demo.match("soomstudio.kr")!!.fakeUrl)

    @Test fun lookalikeIsNotADemoHost() {
        assertNull(demo.match("https://notdbs-secure-verify.top/"))
        assertNull(demo.match("https://www.naver.com/"))
        assertNull(demo.match("http://10.0.2.2:8081/unknown/"))
    }
}
