package dev.redflag.app

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.redflag.app.accessibility.RedFlagAccessibilityService
import dev.redflag.app.chat.ChatSession
import dev.redflag.app.chat.SharedInbox
import dev.redflag.app.ui.screens.ChatHomeScreen
import dev.redflag.app.ui.screens.SettingsScreen
import dev.redflag.app.ui.screens.OnboardingScreen
import dev.redflag.app.ui.theme.RedFlagTheme

class MainActivity : ComponentActivity() {
    /** The chat home's conversation (in memory; the hidden WebView is laid out in this activity's content view). */
    private val chat by lazy { ChatSession(this, findViewById(android.R.id.content)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RedFlagTheme { MainRoot(chat) } }
        handleFocus(intent)
        takeShared()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleFocus(intent)
        takeShared()
    }

    override fun onResume() {
        super.onResume()
        chat.onResume()
    }

    override fun onDestroy() {
        chat.close()
        super.onDestroy()
    }

    /** Text shared to RedFlag arrives through the in-process inbox, never through this (exported) activity's extras. */
    private fun takeShared() {
        SharedInbox.take()?.let { chat.send(it.text, it.caller) }
    }

    /** App shortcut "Check a link": focus the home card's field (the clipboard is NOT read). */
    private fun handleFocus(intent: android.content.Intent?) {
        if (intent?.action == ACTION_CHECK_LINK) dev.redflag.app.ui.screens.HomeFocus.requests.value += 1
    }

    companion object { const val ACTION_CHECK_LINK = "dev.redflag.action.CHECK_LINK" }
}

object Protection {
    fun isDefaultBrowser(ctx: Context): Boolean =
        ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true

    fun isAccessibilityOn(ctx: Context): Boolean {
        val enabled = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(ctx, RedFlagAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

private enum class Screen { ONBOARDING, CHAT, SETTINGS }

@Composable
fun MainRoot(chat: ChatSession) {
    val g = LocalContext.current.graph
    var screen by remember { mutableStateOf(if (g.settings.onboarded) Screen.CHAT else Screen.ONBOARDING) }
    AnimatedContent(screen, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(180)) }, label = "root") { s ->
        when (s) {
            Screen.ONBOARDING -> OnboardingScreen(onDone = { g.settings.onboarded = true; screen = Screen.CHAT })
            Screen.CHAT -> ChatHomeScreen(chat, onSettings = { screen = Screen.SETTINGS })
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.CHAT })
        }
    }
}
