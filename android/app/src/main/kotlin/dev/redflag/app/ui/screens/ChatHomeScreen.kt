package dev.redflag.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.redflag.app.R
import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.analysis.CloudUi
import dev.redflag.app.analysis.ContextUi
import dev.redflag.app.analysis.Phase
import dev.redflag.app.chat.ChatItem
import dev.redflag.app.chat.ChatSession
import dev.redflag.app.check.LinkLabels
import dev.redflag.app.graph
import dev.redflag.app.lite.StepStatus
import dev.redflag.app.lite.Unverified
import dev.redflag.app.ui.components.BrandMark
import dev.redflag.app.ui.components.ContextPrompt
import dev.redflag.app.ui.components.ContextResult
import dev.redflag.app.ui.components.HostChip
import dev.redflag.app.ui.components.LevelPill
import dev.redflag.app.ui.components.RedFlagList
import dev.redflag.app.ui.components.isKorean
import dev.redflag.app.ui.components.levelLabel
import dev.redflag.app.ui.components.stepLabel
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RfType
import dev.redflag.core.model.RiskReport
import dev.redflag.core.report.isVerifiedSafe

/**
 * Home = a conversation (docs/superpowers/specs/2026-11-05-chat-home-design.md). RedFlag's bubbles are built
 * from verdicts only (no free-form model replies); the full analysis screen opens on [자세히].
 */
@Composable
fun ChatHomeScreen(session: ChatSession, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val p = rememberProtection()
    // the history as it was when the screen opened: this session's checks are already in the conversation
    val history = remember { g.reports.recent.value.take(HISTORY).reversed() }
    val focusRequests by HomeFocus.requests.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val items = session.items
    val detail by session.detail

    // follow the conversation: a new bubble, or the newest check growing (verdict, question, re-judgment)
    val last = items.lastOrNull { it is ChatItem.Analysis || it is ChatItem.Rejudge }
    val lastUi = when (last) {
        is ChatItem.Analysis -> last.controller.state.collectAsStateWithLifecycle().value
        is ChatItem.Rejudge -> last.controller.state.collectAsStateWithLifecycle().value
        else -> null
    }
    val header = 2 + (if (!p.protecting) 1 else 0) + (if (history.isNotEmpty()) history.size + 1 else 0)
    LaunchedEffect(items.size, lastUi?.phase, lastUi?.context, lastUi?.cloud?.javaClass) {
        if (items.isNotEmpty()) list.animateScrollToItem(header + items.size - 1)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(p.protecting, onSettings)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") { RedFlagBubble { Text(stringResource(R.string.chat_intro), style = RfType.body, color = MaterialTheme.colorScheme.onSurface) } }
                item(key = "intro2") {
                    RedFlagBubble { Text(stringResource(R.string.chat_intro_more), style = RfType.body.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (!p.protecting) item(key = "setup") {
                    RedFlagBubble {
                        Text(stringResource(R.string.chat_setup, 3 - p.count), style = RfType.body, color = LocalRedFlagColors.current.caution.fg)
                        TextButton(onSettings) { Text(stringResource(R.string.settings_title)) }
                    }
                }
                if (history.isNotEmpty()) {
                    item(key = "history") { Divider(stringResource(R.string.chat_history)) }
                    items(history, key = { "h-" + it.id }) { r -> HistoryBubble(r) }
                }
                items(items, key = { it.id }) { item -> ChatBubble(item, session) }
            }
            ChatComposer(onSend = { session.send(it) }, focusRequests = focusRequests)
        }
        // [자세히]: the full analysis screen, over the chat
        AnimatedVisibility(detail != null, enter = fadeIn(tween(200)) + slideInVertically { it / 8 }, exit = fadeOut(tween(150)) + slideOutVertically { it / 8 }) {
            val d = detail ?: return@AnimatedVisibility
            BackHandler { session.detail.value = null }
            val ui by d.controller.state.collectAsStateWithLifecycle()
            AnalysisScreen(ui, d.actions)
        }
    }
}

private const val HISTORY = 8

@Composable
private fun TopBar(protecting: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(32.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = RfType.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSettings)) {
            LevelPill(if (protecting) "safe" else "caution", stringResource(if (protecting) R.string.chat_protected else R.string.chat_setup_needed))
        }
        IconButton(onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

@Composable
private fun Divider(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text(label, style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

// ---------------------------------------------------------------- bubbles

private val RedFlagShape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
private val UserShape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp)

@Composable
private fun RedFlagBubble(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        BrandMark(26.dp, Modifier.padding(top = 4.dp))
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier.widthIn(max = 340.dp).clip(RedFlagShape).background(MaterialTheme.colorScheme.surface)
                .animateContentSize(tween(260, easing = FastOutSlowInEasing)).padding(horizontal = 14.dp, vertical = 12.dp),
        ) { content() }
    }
}

@Composable
private fun ChatBubble(item: ChatItem, session: ChatSession) {
    when (item) {
        is ChatItem.User -> UserBubble(item.text)
        is ChatItem.NoLink -> RedFlagBubble {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LinkOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_no_link), style = RfType.body, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        is ChatItem.Pick -> PickBubble(item, session)
        is ChatItem.Resolving -> RedFlagBubble {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.chat_resolving), style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is ChatItem.Known -> KnownBubble(item, session)
        is ChatItem.Analysis -> {
            val ui by item.controller.state.collectAsStateWithLifecycle()
            VerdictBubble(ui, item, session)
        }
        is ChatItem.Rejudge -> {
            val ui by item.controller.state.collectAsStateWithLifecycle()
            RejudgeBubble(ui)
        }
        is ChatItem.Cancelled -> RedFlagBubble {
            Text(stringResource(R.string.chat_cancelled, item.host), style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text, style = RfType.body, color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 300.dp).clip(UserShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickBubble(item: ChatItem.Pick, session: ChatSession) = RedFlagBubble {
    Text(stringResource(R.string.check_pick_title, item.links.size), style = RfType.body, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.links.forEach { u ->
            AssistChip(onClick = { session.pick(item, u) }, label = { Text(LinkLabels.short(u, 34), style = RfType.mono.copy(fontSize = 12.sp), maxLines = 1) })
        }
    }
}

@Composable
private fun KnownBubble(k: ChatItem.Known, session: ChatSession) = RedFlagBubble {
    val g = LocalContext.current.graph
    val safe = LocalRedFlagColors.current.safe
    val domain = k.gate.normalized.registrableDomain ?: k.gate.normalized.host
    val brand = remember(domain) { g.bundle.brands.brands.firstOrNull { b -> b.domains.any { it.equals(domain, ignoreCase = true) } }?.name }
    HostChip(k.gate.normalized.host)
    k.chain?.let { Text(it, style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Verified, null, tint = safe.fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.check_known_title), style = RfType.title, color = MaterialTheme.colorScheme.onSurface)
    }
    Text(
        if (brand != null) stringResource(R.string.check_known_brand, brand, domain) else stringResource(R.string.check_known_popular, domain),
        style = RfType.body.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val ctx = LocalContext.current
        Button({ dev.redflag.app.link.ChromeLauncher.open(ctx, k.url) }) { Text(stringResource(R.string.inspect_open_chrome), style = RfType.label) }
        TextButton({ session.checkAnyway(k) }) { Text(stringResource(R.string.check_known_anyway), style = RfType.label) }
    }
}

/** One check: live steps while running, then score + headline + top reasons, a context question if any, and buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerdictBubble(ui: AnalysisUi, item: ChatItem.Analysis, session: ChatSession) = RedFlagBubble {
    val colors = LocalRedFlagColors.current
    val a = item.actions
    Row(verticalAlignment = Alignment.CenterVertically) {
        HostChip(ui.host)
        Spacer(Modifier.weight(1f))
        if (ui.phase == Phase.RESULT) LevelPill(if (ui.unverified) null else ui.displayLevel)
    }
    item.chain?.let { Text(it, style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(10.dp))
    AnimatedContent(ui.phase == Phase.RESULT, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(120)) }, label = "verdict") { done ->
        Column {
            if (!done) Checking(ui)
            else if (ui.unverified) {
                val (t, b) = when (Unverified.reason(ui.lite)) {
                    Unverified.Reason.UNREACHABLE -> R.string.unverified_unreachable_title to R.string.unverified_unreachable_body
                    Unverified.Reason.BLOCKED, Unverified.Reason.CHALLENGE -> R.string.unverified_blocked_title to R.string.unverified_blocked_body
                    else -> R.string.unverified_title to R.string.unverified_body
                }
                Text(stringResource(t), style = RfType.title, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(b), style = RfType.body.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            } else {
                val r = shown(ui)
                val c = colors.level(ui.displayLevel)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ui.displayScore}", style = RfType.headline.copy(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold), color = c.fg)
                    Spacer(Modifier.width(12.dp))
                    Text(r?.let { if (isKorean()) it.headline.ko else it.headline.en }.orEmpty(), style = RfType.title, color = MaterialTheme.colorScheme.onSurface)
                }
                val flags = r?.redFlags.orEmpty()
                if (flags.isNotEmpty()) { Spacer(Modifier.height(8.dp)); RedFlagList(flags, max = 3, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
    // a context question (who sent it?) belongs to this check
    if (ui.context is ContextUi.Consent || ui.context is ContextUi.Ask || ui.context is ContextUi.Checking || ui.context is ContextUi.Judging) {
        Spacer(Modifier.height(10.dp))
        ContextPrompt(ui, a.context)
    }
    ContextResult(ui, a.context)
    when (val cl = ui.cloud) {
        is CloudUi.Running -> Text(stringResource(R.string.chat_cloud_running), style = RfType.label, color = colors.cloud, modifier = Modifier.padding(top = 8.dp))
        is CloudUi.Done -> Text(stringResource(R.string.chat_cloud_done, levelLabel(cl.report.level), cl.report.score), style = RfType.label, color = colors.cloud, modifier = Modifier.padding(top = 8.dp))
        is CloudUi.Failed -> Text(stringResource(R.string.chat_cloud_failed), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        else -> Unit
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (ui.phase == Phase.RUNNING) {
            TextButton(a.onSkip) { Text(stringResource(R.string.inspect_cancel), style = RfType.label) }
            TextButton({ session.detail.value = item }) { Text(stringResource(R.string.chat_detail), style = RfType.label) }
        } else {
            FilledTonalButton({ session.detail.value = item }) { Text(stringResource(R.string.chat_detail), style = RfType.label) }
            val safe = ui.lite?.isVerifiedSafe == true && ui.displayLevel == "safe"
            val danger = ui.displayLevel == "danger" || ui.displayLevel == "critical"
            if (safe) Button(a.onContinue, colors = ButtonDefaults.buttonColors(containerColor = colors.safe.fg, contentColor = MaterialTheme.colorScheme.surface)) {
                Text(stringResource(R.string.inspect_open_chrome), style = RfType.label)
            } else TextButton(a.onOpenAnyway) {
                Text(stringResource(if (danger) R.string.block_open_anyway else R.string.caution_open), style = RfType.label,
                    color = if (danger) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            if (a.cloudAvailable && ui.cloud is CloudUi.Idle) TextButton(a.onDeepAnalysis) {
                Text(stringResource(R.string.second_opinion), style = RfType.label, color = colors.cloud)
            }
        }
    }
}

/** The report the card summarises: the stricter of Lite and Cloud (same as the full screen). */
private fun shown(ui: AnalysisUi): RiskReport? = ui.cloudReport?.takeIf { it.score >= (ui.lite?.score ?: 0) } ?: ui.lite

@Composable
private fun Checking(ui: AnalysisUi) {
    val done = ui.steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED || it.status == StepStatus.FAILED }
    val fraction by animateFloatAsState(done / ui.steps.size.coerceAtLeast(1).toFloat(), tween(500, easing = FastOutSlowInEasing), label = "chatProgress")
    val running = ui.steps.firstOrNull { it.status == StepStatus.RUNNING }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.waiting_title), style = RfType.title, color = MaterialTheme.colorScheme.onSurface)
    }
    Text(
        running?.let { (if (isKorean()) "지금: " else "Now: ") + stepLabel(it.id) } ?: stringResource(R.string.waiting_subtitle),
        style = RfType.body.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
    LinearProgressIndicator(
        progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), drawStopIndicator = {},
    )
}

/** After the user explained their situation: what the re-judgment did. */
@Composable
private fun RejudgeBubble(ui: AnalysisUi) = RedFlagBubble {
    val ctx = ui.context
    val text = when {
        ui.phase == Phase.RUNNING || ui.contextPending -> stringResource(R.string.chat_rejudge_pending)
        ctx is ContextUi.Judging || ctx is ContextUi.Checking -> stringResource(R.string.chat_rejudge_running)
        ctx is ContextUi.Done && (ctx.summary?.added ?: 0) > 0 ->
            stringResource(R.string.chat_rejudge_raised, ctx.summary!!.added, levelLabel(ui.displayLevel), ui.displayScore)
        ctx is ContextUi.Done && ctx.summary != null -> stringResource(R.string.chat_rejudge_same, levelLabel(ui.displayLevel), ui.displayScore)
        ctx is ContextUi.Done -> stringResource(R.string.chat_rejudge_unavailable)
        else -> stringResource(R.string.chat_rejudge_running)
    }
    val busy = ui.phase == Phase.RUNNING || ui.contextPending || ctx is ContextUi.Judging || ctx is ContextUi.Checking
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = RfType.body, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HistoryBubble(r: RiskReport) {
    Box(Modifier.fillMaxWidth().padding(start = 34.dp)) {
        Box(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp)) {
            RecentRow(r)
        }
    }
}
