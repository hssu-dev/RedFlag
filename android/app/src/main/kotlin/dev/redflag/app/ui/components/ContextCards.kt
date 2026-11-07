package dev.redflag.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.analysis.ContextUi
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RfType
import dev.redflag.app.ui.theme.TokensDim
import dev.redflag.core.followup.FollowupContracts
import dev.redflag.core.model.I18n

/** Context analyzer actions (docs/plans/context_analysis.md). Defaults are no-ops (previews / tests). */
class ContextActions(
    val onAllow: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onNeverAsk: () -> Unit = {},
    val onOpenAccessSettings: () -> Unit = {},
    val onDirectInput: () -> Unit = {},
    val onSubmit: (questionId: String, chipIds: List<String>, text: String) -> Unit = { _, _, _ -> },
    /** The free-text field is on screen (an overlay window must become focusable for the keyboard). */
    val onKeyboard: (Boolean) -> Unit = {},
    /** In-app check: [remove] on the "pasted message in use" chip. */
    val onRemovePasted: () -> Unit = {},
)

@Composable
private fun tr(t: I18n): String = if (isKorean()) t.ko else t.en

@Composable
private fun tr(ko: String, en: String): String = if (isKorean()) ko else en

/** The interactive / progress part: shown above the verdict so it is seen first. */
@Composable
fun ContextPrompt(ui: AnalysisUi, a: ContextActions, modifier: Modifier = Modifier) {
    when (val c = ui.context) {
        ContextUi.Checking -> ProgressLine(tr("이 링크를 받은 상황이 중요한지 확인하고 있어요…", "Checking whether how you got this link matters…"), modifier)
        ContextUi.Judging -> ProgressLine(tr("받은 상황을 함께 분석하고 있어요…", "Analyzing the situation you got this link in…"), modifier)
        is ContextUi.Consent -> ConsentCard(ui, c, a, modifier)
        is ContextUi.Ask -> QuestionCard(c, a, modifier)
        else -> Unit
    }
}

@Composable
private fun ProgressLine(text: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CardShell(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(TokensDim.shape_large.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp,
    ) { Column(Modifier.padding(18.dp).animateContentSize()) { content() } }
}

@Composable
private fun ConsentCard(ui: AnalysisUi, c: ContextUi.Consent, a: ContextActions, modifier: Modifier) {
    val cat = FollowupContracts.catalog
    val ko = isKorean()
    val app = ui.source.name?.let { if (ko) it.ko else it.en }
        ?: cat.categories[ui.source.category]?.let { if (ko) it.ko else it.en } ?: ""
    val reason = c.plan.reasons.firstOrNull()?.let { cat.reasons[it] }?.let { if (ko) it.ko else it.en } ?: ""
    val body = tr(cat.consent.body).replace("{app}", app).replace("{reason}", reason)
    CardShell(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Forum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr(cat.consent.title), style = RfType.title, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.needsAccess) {
            Spacer(Modifier.height(10.dp))
            Text(tr(cat.consent.needsAccess), style = RfType.label, color = LocalRedFlagColors.current.caution.fg)
            TextButton(a.onOpenAccessSettings) { Text(tr(cat.consent.openSettings)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(a.onAllow, Modifier.weight(1f), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) { Text(tr(cat.consent.allow)) }
            OutlinedButton(a.onSkip, Modifier.weight(1f), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) { Text(tr(cat.consent.skip)) }
        }
        TextButton(a.onNeverAsk, Modifier.fillMaxWidth()) {
            Text(tr(cat.consent.never), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(c: ContextUi.Ask, a: ContextActions, modifier: Modifier) {
    val cat = FollowupContracts.catalog
    val q = cat.questions[c.questionId] ?: return
    val chips = remember(c.questionId) { mutableStateListOf<String>() }
    var text by remember(c.questionId) { mutableStateOf("") }
    val limit = cat.limits.statementChars
    DisposableEffect(c.questionId) {
        a.onKeyboard(true)
        onDispose { a.onKeyboard(false) }
    }
    CardShell(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (c.userInitiated) tr(cat.freeInput.title) else tr("확인 하나만 할게요", "One quick question"),
                style = RfType.label, color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(tr(q.text), style = RfType.title, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            q.chips.forEach { chip ->
                val on = chip.id in chips
                FilterChip(
                    selected = on,
                    onClick = { if (on) chips.remove(chip.id) else chips.add(chip.id) },
                    label = { Text(tr(chip.label)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(limit) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr(cat.freeInput.hint), style = RfType.label) },
            supportingText = { Text("${text.length}/$limit", style = RfType.label.copy(fontSize = 11.sp)) },
            minLines = 2, maxLines = 5,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                { a.onSubmit(c.questionId, chips.toList(), text) }, Modifier.weight(1f),
                enabled = chips.isNotEmpty() || text.isNotBlank(), shape = RoundedCornerShape(TokensDim.shape_medium.dp),
            ) { Text(tr(cat.freeInput.send)) }
            OutlinedButton(a.onSkip, Modifier.weight(1f), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) { Text(tr(cat.freeInput.skip)) }
        }
    }
}

/** Result of the context step (below the verdict) + the always-available [상황 직접 입력] button. */
@Composable
fun ContextResult(ui: AnalysisUi, a: ContextActions, modifier: Modifier = Modifier) {
    val cat = FollowupContracts.catalog
    Column(modifier.fillMaxWidth()) {
        ui.pasted?.let { PastedChip(it, a) ; Spacer(Modifier.height(10.dp)) }
        val done = ui.context as? ContextUi.Done
        val s = done?.summary
        if (s != null) {
            CardShell(Modifier) {
                Text(tr("받은 상황 분석", "Context check"), style = RfType.label, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                val scen = s.scenario?.let { cat.scenarios[it] }
                Text(
                    when {
                        scen != null -> tr("${tr(scen)} 수법과 비슷한 상황이에요", "This matches a ${tr(scen)} pattern")
                        s.added > 0 -> tr("받은 상황 때문에 위험도가 올라갔어요", "How you got this link raised the risk")
                        else -> tr("받은 상황에서 추가 위험 신호는 찾지 못했어요", "No extra warning signs in how you got this link")
                    },
                    style = RfType.body.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface,
                )
                if (s.added > 0) Text(tr("+${s.added}점", "+${s.added} points"), style = RfType.label, color = LocalRedFlagColors.current.danger.fg)
                s.evidence.forEach { e ->
                    Text("“$e”", style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    listOfNotNull(
                        if (s.usedPasted) tr("붙여넣은 메시지 확인", "pasted message checked")
                        else if (s.usedConversation) tr("대화 확인", "chat checked") else null,
                        if (s.usedStatement) tr("직접 입력 반영", "your answer used") else null,
                        tr("기기 안에서만 분석", "analyzed on this phone only"),
                    ).joinToString(" · "),
                    style = RfType.label.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else if (done?.note == "no_conversation") {
            Text(tr("링크가 담긴 대화를 찾지 못했어요", "Couldn't find the chat that carried this link"), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (done?.note == "excluded" || done?.note == "already_high") {
            Text(
                if (done.note == "already_high") tr("이미 매우 위험해서 상황 분석은 생략했어요", "Already critical: the situation check was skipped")
                else tr("이 사이트는 상황 분석을 생략했어요", "The situation check was skipped for this site"),
                style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.contextPending) {
            Text(tr("입력한 상황은 분석이 끝나면 반영돼요", "Your answer will be used when the check finishes"), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val canInput = !ui.revisit && (ui.lite == null || ui.lite.status == "complete")
        if (ui.context.settled && canInput) {
            TextButton(a.onDirectInput, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.EditNote, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(tr(cat.freeInput.title), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Pasted message in use · [remove]" (in-app link check, docs/plans/manual_url_check.md §4). */
@Composable
private fun PastedChip(p: dev.redflag.app.analysis.PastedUi, a: ContextActions) {
    var open by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(TokensDim.shape_medium.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Forum, null, tint = if (p.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (p.active) tr("붙여넣은 메시지를 상황 분석에 사용 중", "Using the pasted message as context")
                    else tr("붙여넣은 메시지는 빼고 분석해요", "The pasted message is left out"),
                    style = RfType.label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                )
                TextButton({ open = !open }) { Text(if (open) tr("접기", "Hide") else tr("보기", "Show"), style = RfType.label) }
                if (p.active) TextButton(a.onRemovePasted) { Text(tr("빼기", "Remove"), style = RfType.label, color = LocalRedFlagColors.current.danger.fg) }
            }
            if (open) Text(p.preview, style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
