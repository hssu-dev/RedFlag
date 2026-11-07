package dev.redflag.app.ui.screens

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.redflag.app.BuildConfig
import dev.redflag.app.Protection
import dev.redflag.app.R
import dev.redflag.app.context.ContextNotificationListener
import dev.redflag.app.context.NotificationBuffer
import dev.redflag.app.graph
import dev.redflag.app.link.LinkHandlerActivity
import dev.redflag.app.lite.LiteRtGemmaEngine
import dev.redflag.app.settings.EnginePref
import dev.redflag.app.settings.Mode
import dev.redflag.app.ui.components.BrandMark
import dev.redflag.app.ui.components.BreathingGlow
import dev.redflag.app.ui.components.LevelPill
import dev.redflag.app.ui.components.Staggered
import dev.redflag.app.ui.components.isKorean
import dev.redflag.app.ui.components.levelLabel
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RfType
import dev.redflag.app.ui.theme.TokensDim
import dev.redflag.core.model.RiskReport
import java.time.Duration
import java.time.Instant

/** The three switches RedFlag needs, re-read whenever the screen resumes (the user comes back from Settings). */
data class ProtectionState(val browser: Boolean, val a11y: Boolean, val notifications: Boolean) {
    /** Browser role + Chrome cover = links are checked. Notification access only adds context. */
    val protecting: Boolean get() = browser && a11y
    val count: Int get() = listOf(browser, a11y, notifications).count { it }
}

@Composable
fun rememberProtection(): ProtectionState {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return remember(tick) {
        ProtectionState(Protection.isDefaultBrowser(ctx), Protection.isAccessibilityOn(ctx), ContextNotificationListener.hasAccess(ctx))
    }
}

@Composable
private fun tr(ko: String, en: String) = if (isKorean()) ko else en

/** Opens the system screen for each switch. */
@Composable
private fun rememberSetupActions(): Triple<() -> Unit, () -> Unit, () -> Unit> {
    val ctx = LocalContext.current
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    return remember {
        Triple(
            {
                val rm = ctx.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                else ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            },
            { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
            { runCatching { ctx.startActivity(ContextNotificationListener.settingsIntent(ctx)) } },
        )
    }
}

// ---------------------------------------------------------------- shared pieces

/** Big status card: the mark breathing in green when protected, amber while setup is incomplete. */
@Composable
private fun ProtectionHero(p: ProtectionState) {
    val colors = LocalRedFlagColors.current
    val c = if (p.protecting) colors.safe else colors.caution
    val progress by animateFloatAsState(p.count / 3f, tween(600), label = "setup")
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Box(Modifier.background(Brush.verticalGradient(listOf(c.container.copy(alpha = 0.55f), MaterialTheme.colorScheme.surface)))) {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    BreathingGlow(c.fg, size = 108.dp)
                    BrandMark(64.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (p.protecting) tr("보호 중", "Protected") else tr("보호를 켜 주세요", "Turn on protection"),
                        style = RfType.headline, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (p.protecting) tr("낯선 링크는 기기 안 Gemma가 먼저 확인해요", "Unfamiliar links are checked on this phone first")
                        else tr("아래 스위치를 켜면 바로 시작돼요", "Flip the switches below to start"),
                        style = RfType.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress }, color = c.fg, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), drawStopIndicator = {},
                    )
                    Text(tr("설정 ${p.count}/3", "Setup ${p.count}/3"), style = RfType.label.copy(fontSize = 11.sp), color = c.fg, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, body: String, on: Boolean, optional: Boolean = false, onEnable: () -> Unit) {
    val colors = LocalRedFlagColors.current
    val iconBg by animateColorAsState(if (on) colors.safe.container else MaterialTheme.colorScheme.surfaceVariant, tween(400), label = "iconBg")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(TokensDim.shape_medium.dp)).clickable(enabled = !on, onClick = onEnable).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) colors.safe.fg else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = RfType.title.copy(fontSize = 16.sp), color = MaterialTheme.colorScheme.onSurface)
                if (optional) Text(tr("  선택", "  optional"), style = RfType.label.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(body, style = RfType.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        if (on) Icon(Icons.Rounded.CheckCircle, null, tint = colors.safe.fg, modifier = Modifier.size(26.dp))
        else FilledTonalButton(onEnable, Modifier.height(36.dp)) { Text(stringResource(R.string.onboarding_enable), style = RfType.label) }
    }
}

@Composable
private fun SetupList(p: ProtectionState) {
    val (browser, a11y, notif) = rememberSetupActions()
    Column {
        SetupRow(Icons.Outlined.OpenInBrowser, stringResource(R.string.onboarding_browser_title), stringResource(R.string.onboarding_browser_body), p.browser, onEnable = browser)
        SetupRow(Icons.Outlined.Accessibility, stringResource(R.string.onboarding_a11y_title), stringResource(R.string.onboarding_a11y_body), p.a11y, onEnable = a11y)
        SetupRow(Icons.Outlined.Forum, stringResource(R.string.context_access_title), stringResource(R.string.context_access_body), p.notifications, optional = true, onEnable = notif)
    }
}

@Composable
private fun PrivacyNote() {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Icons.Rounded.Lock, null, tint = LocalRedFlagColors.current.safe.fg, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.onboarding_privacy), style = RfType.body.copy(fontSize = 13.sp, lineHeight = 19.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Card(title: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(TokensDim.shape_large.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = RfType.title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

// ---------------------------------------------------------------- onboarding

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val p = rememberProtection()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Staggered(0) {
            Box(contentAlignment = Alignment.Center) {
                BreathingGlow(MaterialTheme.colorScheme.primary, size = 150.dp)
                BrandMark(84.dp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Staggered(1) {
            Column {
                Text(stringResource(R.string.onboarding_title), style = RfType.headline.copy(fontSize = 30.sp, lineHeight = 36.sp), color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tagline), style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(22.dp))
        Staggered(2) { Card { SetupList(p) } }
        Spacer(Modifier.height(18.dp))
        Staggered(3) { PrivacyNote() }
        Spacer(Modifier.height(24.dp))
        Staggered(4) {
            Button(onDone, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
                Text(stringResource(R.string.onboarding_done), style = RfType.title)
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val p = rememberProtection()
    val mode by g.settings.modeFlow.collectAsStateWithLifecycle()
    val recent by g.reports.recent.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onBack) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_back))
            }
            Text(stringResource(R.string.settings_title), style = RfType.headline, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            LevelPill(if (p.protecting) "safe" else "caution", if (p.protecting) tr("켜짐", "On") else tr("설정 필요", "Setup"))
        }
        Staggered(0) { ProtectionHero(p) }
        AnimatedVisibility(p.count < 3, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Card(tr("보호 설정", "Protection")) { SetupList(p) }
        }
        Staggered(1) { StatsRow(recent) }
        Staggered(3) {
            Card(tr("분석 설정", "Analysis")) {
                Text(stringResource(R.string.settings_mode), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Mode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = mode == m, onClick = { g.settings.mode = m }, shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)) {
                            Text(stringResource(when (m) { Mode.LITE -> R.string.mode_lite; Mode.ON_REQUEST -> R.string.mode_on_request }), style = RfType.label)
                        }
                    }
                }
                Text(
                    stringResource(when (mode) { Mode.LITE -> R.string.mode_lite_desc; Mode.ON_REQUEST -> R.string.mode_on_request_desc }),
                    style = RfType.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))
                var ctxOn by remember { mutableStateOf(g.settings.contextEnabled) }
                var never by remember { mutableStateOf(g.settings.contextNeverAsk) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.context_settings_title), style = RfType.title.copy(fontSize = 16.sp), color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.context_settings_body), style = RfType.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = ctxOn, onCheckedChange = { ctxOn = it; g.settings.contextEnabled = it; if (!it) NotificationBuffer.clear() })
                }
                if (never) TextButton({ g.settings.contextNeverAsk = false; never = false }) { Text(stringResource(R.string.context_reset_never)) }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))
                var fullCover by remember { mutableStateOf(g.settings.fullCoverWhileChecking) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_full_cover), style = RfType.title.copy(fontSize = 16.sp), color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.settings_full_cover_body), style = RfType.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = fullCover, onCheckedChange = { fullCover = it; g.settings.fullCoverWhileChecking = it })
                }
            }
        }
        DeveloperCard()
        PrivacyNote()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatsRow(recent: List<RiskReport>) {
    val colors = LocalRedFlagColors.current
    val checked = recent.size
    val warned = recent.count { it.level == "caution" }
    val blocked = recent.count { it.level == "danger" || it.level == "critical" }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(tr("확인", "Checked"), checked, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        StatTile(tr("주의", "Warned"), warned, colors.caution.fg, Modifier.weight(1f))
        StatTile(tr("차단", "Blocked"), blocked, colors.danger.fg, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val shown by animateFloatAsState(value.toFloat(), tween(700), label = "stat")
    Surface(modifier, shape = RoundedCornerShape(TokensDim.shape_medium.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 14.dp)) {
            Text("${shown.toInt()}", style = RfType.headline.copy(fontSize = 28.sp, lineHeight = 32.sp), color = color)
            Text(label, style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun timeAgo(iso: String, ko: Boolean): String {
    val d = runCatching { Duration.between(Instant.parse(iso), Instant.now()) }.getOrNull() ?: return ""
    val m = d.toMinutes()
    return when {
        m < 1 -> if (ko) "방금" else "just now"
        m < 60 -> if (ko) "${m}분 전" else "${m}m ago"
        m < 60 * 24 -> if (ko) "${m / 60}시간 전" else "${m / 60}h ago"
        else -> if (ko) "${m / (60 * 24)}일 전" else "${m / (60 * 24)}d ago"
    }
}

@Composable
internal fun RecentRow(r: RiskReport) {
    val c = LocalRedFlagColors.current.level(r.level)
    val ko = isKorean()
    val ctx = LocalContext.current
    val domain = r.registrableDomain ?: Uri.parse(r.url).host ?: r.url
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable { ctx.startActivity(dev.redflag.app.check.CheckActivity.forReport(ctx, r.id)) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.container), contentAlignment = Alignment.Center) {
            Text(domain.take(1).uppercase(), style = RfType.title, color = c.fg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(domain, style = RfType.mono, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (ko) r.headline.ko else r.headline.en) + " · " + timeAgo(r.createdAt, ko) + if (r.mode == "cloud") " · ☁" else "",
                style = RfType.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${r.score}", style = RfType.title.copy(fontWeight = FontWeight.Bold), color = c.fg)
            Text(levelLabel(r.level), style = RfType.label.copy(fontSize = 11.sp), color = c.fg)
        }
    }
}

/** Engine / backend / demo links / cache: tucked away, collapsed by default. */
@Composable
private fun DeveloperCard() {
    val ctx = LocalContext.current
    val g = ctx.graph
    var open by rememberSaveable { mutableStateOf(false) }
    val engine by g.settings.engineFlow.collectAsStateWithLifecycle()
    val backend by g.settings.backendFlow.collectAsStateWithLifecycle()
    var backendText by remember(backend) { mutableStateOf(backend) }
    Card(
        tr("개발자 옵션", "Developer options"),
        trailing = {
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).clickable { open = !open }.padding(4.dp))
        },
    ) {
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                var always by remember { mutableStateOf(g.settings.alwaysReanalyze) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_always_reanalyze), style = RfType.title.copy(fontSize = 16.sp), color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.settings_always_reanalyze_body), style = RfType.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = always, onCheckedChange = { always = it; g.settings.alwaysReanalyze = it })
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.settings_engine), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val modelOk = remember { g.realGemma.modelAvailable() }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    EnginePref.entries.forEachIndexed { i, e ->
                        SegmentedButton(selected = engine == e, onClick = { g.settings.engine = e }, shape = SegmentedButtonDefaults.itemShape(i, EnginePref.entries.size)) {
                            Text(when (e) { EnginePref.AUTO -> "Auto"; EnginePref.REAL -> "Gemma"; EnginePref.FAKE -> "Demo" }, style = RfType.label)
                        }
                    }
                }
                Text(
                    if (modelOk) stringResource(R.string.engine_real) + " · ${LiteRtGemmaEngine.MODEL_FILE}"
                    else stringResource(R.string.engine_model_missing, LiteRtGemmaEngine.modelDir(ctx).absolutePath),
                    style = RfType.mono.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.settings_backend), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(backendText, { backendText = it; g.settings.backendUrl = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = RfType.mono)
                if (BuildConfig.DEMO_HOSTS) g.demoHosts?.let { demo ->
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.home_try), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    demo.hosts.forEach { (host, h) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                ctx.startActivity(Intent(ctx, LinkHandlerActivity::class.java).setData(Uri.parse("https://$host/")))
                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(host, style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            LevelPill(h.expectedLevel)
                        }
                    }
                }
                TextButton({ g.safeVerdictCache.clear() }) { Text("Clear safe-verdict cache", style = RfType.label) }
            }
        }
    }
}
