package ru.adsrv.frpjump.ui.ssh

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
import ru.adsrv.frpjump.ssh.ClipKind
import ru.adsrv.frpjump.ssh.clipKind

/**
 * Whether the clipboard holds text to paste, from its description only: the
 * content is not read (that would show the system's "pasted from" notice on
 * every check), so text of only spaces counts here and is caught on the tap
 * (see planPaste). Checked again on resume and
 * whenever the clipboard changes while the app is in front.
 */
@Composable
fun clipboardHasText(): Boolean = clipboardKind() == ClipKind.TEXT

/** Text, an image and no text, or neither, by the same description-only check (see [clipboardHasText]). */
@Composable
fun clipboardKind(): ClipKind {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var kind by remember { mutableStateOf(clipboard.kind()) }
    DisposableEffect(clipboard, lifecycle) {
        val changed = ClipboardManager.OnPrimaryClipChangedListener { kind = clipboard.kind() }
        val resumed = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) kind = clipboard.kind()
        }
        clipboard.addPrimaryClipChangedListener(changed)
        lifecycle.addObserver(resumed)
        onDispose {
            clipboard.removePrimaryClipChangedListener(changed)
            lifecycle.removeObserver(resumed)
        }
    }
    return kind
}

private fun ClipboardManager.kind(): ClipKind = try {
    clipKind(primaryClipDescription)
} catch (e: RuntimeException) {
    ClipKind.NONE
}
