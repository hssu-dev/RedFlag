package dev.redflag.app

import dev.redflag.app.ui.components.localizeStepDetail
import org.junit.Test
import kotlin.test.assertEquals

class StepDetailTest {
    @Test
    fun koreanDetails() {
        assertEquals("처음 보는 도메인", localizeStepDetail("unfamiliar domain", true))
        assertEquals("위험 목록에 없음 · Google Safe Browsing v5", localizeStepDetail("not listed · Google Safe Browsing v5", true))
        assertEquals("입력 폼 1개 · 숨은 글 없음", localizeStepDetail("1 form · no hidden text", true))
        assertEquals("9일 전 등록 · Let's Encrypt", localizeStepDetail("registered 9 days ago · Let's Encrypt", true))
        assertEquals("내세운 회사 없음 · 4.5초 · GPU", localizeStepDetail("brand=none · 4485 ms · gpu", true))
        assertEquals("내세운 회사: DBS · 3.9초 · CPU", localizeStepDetail("brand=DBS · 3912 ms · cpu", true))
        assertEquals("95 · 매우 위험", localizeStepDetail("95 · critical", true))
        assertEquals("load_error: net::ERR_X", localizeStepDetail("load_error: net::ERR_X", true)) // unknown: untouched
        assertEquals("unfamiliar domain", localizeStepDetail("unfamiliar domain", false))
    }
}
