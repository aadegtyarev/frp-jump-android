package ru.adsrv.frpjump.ui.ssh

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the clipboard holds text to paste, from its description only: the
 * content is not read (that would show the system's "pasted from" notice on
 * every check), so text of only spaces counts here and is caught on the tap
 * (see planPaste). Checked again on resume and
 * whenever the clipboard changes while the app is in front.
 */
@Composable
fun clipboardHasText(): Boolean {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var hasText by remember { mutableStateOf(clipboard.holdsText()) }
    DisposableEffect(clipboard, lifecycle) {
        val changed = ClipboardManager.OnPrimaryClipChangedListener { hasText = clipboard.holdsText() }
        val resumed = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasText = clipboard.holdsText()
        }
        clipboard.addPrimaryClipChangedListener(changed)
        lifecycle.addObserver(resumed)
        onDispose {
            clipboard.removePrimaryClipChangedListener(changed)
            lifecycle.removeObserver(resumed)
        }
    }
    return hasText
}

private fun ClipboardManager.holdsText(): Boolean = try {
    val description = primaryClipDescription
    description != null && (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) || description.hasMimeType("text/*"))
} catch (e: RuntimeException) {
    false
}
