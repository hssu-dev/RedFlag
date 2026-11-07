package dev.redflag.app

import dev.redflag.app.accessibility.RedFlagAccessibilityService.Companion.gateInputFromBar
import dev.redflag.app.accessibility.RedFlagAccessibilityService.Companion.urlFromBarText
import org.junit.Test
import kotlin.test.assertEquals

class UrlBarTextTest {
    @Test fun plainAddress() = assertEquals("naver.com", urlFromBarText("naver.com"))
    @Test fun securityLabelAppended_ko() = assertEquals("localhost:8081/cj/", urlFromBarText("localhost:8081/cj/. 연결이 안전하지 않음"))
    @Test fun securityLabelAppended_en() = assertEquals("example-shop.com/login", urlFromBarText("example-shop.com/login. Not secure"))
    @Test fun fullUrl() = assertEquals("https://dbs-secure-verify.top/login?ref=sms", urlFromBarText("  https://dbs-secure-verify.top/login?ref=sms "))
    @Test fun empty() = assertEquals("", urlFromBarText(null))

    @Test fun insecureLabelMeansHttp() = assertEquals("http://localhost:8081/cj/", gateInputFromBar("localhost:8081/cj/. 연결이 안전하지 않음"))
    @Test fun dotlessHostGetsScheme() = assertEquals("https://localhost:8081/x", gateInputFromBar("localhost:8081/x"))
    @Test fun normalDomainUnchanged() = assertEquals("dbs-secure-verify.top/login", gateInputFromBar("dbs-secure-verify.top/login"))
    @Test fun insecureLabelOnRealDomainDoesNotForceHttp() = assertEquals("shop.example.com/a", gateInputFromBar("shop.example.com/a. Not secure"))
    @Test fun placeholderLeftAlone() = assertEquals("Search", gateInputFromBar("Search or type web address"))
}
