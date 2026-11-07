package dev.redflag.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.redflag.app.R
import dev.redflag.app.ui.theme.RfType
import dev.redflag.core.gate.UrlExtractor
import kotlinx.coroutines.flow.MutableStateFlow

/** App-shortcut "Check a link": bump to focus the chat input (no clipboard read). */
object HomeFocus {
    val requests = MutableStateFlow(0)
}

/** Reads the clipboard. Called ONLY from the [Paste] button's onClick (NoClipboardAutoReadTest). */
internal fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/**
 * The chat home's input row (docs/superpowers/specs/2026-11-05-chat-home-design.md §1):
 * [paste] [a link, a received message, or how you got it] [send]. The clipboard is read only by [paste].
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatComposer(onSend: (String) -> Unit, focusRequests: Int = 0, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequests) { if (focusRequests > 0) runCatching { focus.requestFocus() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // a focused field blinks its cursor, which redraws the whole window every frame even while nothing else moves
    // (perfetto 2026-11-05: 60 fps for minutes on an idle chat); let go of focus whenever the keyboard goes away
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }
    fun send() {
        if (text.isBlank()) return
        onSend(text)
        text = ""
        keyboard?.hide()
        focusManager.clearFocus()
    }
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()
            .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(
            { readClipboardText(ctx)?.let { text = it.take(UrlExtractor.MAX_INPUT) } },
            Modifier.padding(bottom = 4.dp),
        ) { Icon(Icons.Outlined.ContentPaste, stringResource(R.string.check_card_paste), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedTextField(
            text, { text = it.take(UrlExtractor.MAX_INPUT) },
            Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.chat_hint), style = RfType.body) },
            textStyle = RfType.body, maxLines = 5, shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            ::send, Modifier.padding(bottom = 4.dp).size(48.dp), enabled = text.isNotBlank(), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.chat_send)) }
    }
}
