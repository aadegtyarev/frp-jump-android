package ru.adsrv.frpjump.ui.ssh

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.image.EncodedImage
import ru.adsrv.frpjump.image.ImageEncoder
import ru.adsrv.frpjump.image.ImageFailure
import ru.adsrv.frpjump.image.ImageInsertJob
import ru.adsrv.frpjump.image.ImageInsertOutcome
import ru.adsrv.frpjump.image.ImageInsertState
import ru.adsrv.frpjump.image.ImageTarget
import ru.adsrv.frpjump.image.onlyWhile
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.MainButton
import ru.adsrv.frpjump.ui.notice

/** One image going to one tab: what the bar shows and the job to cancel. */
class ImageRun {
    var state: ImageInsertState by mutableStateOf(ImageInsertState.Encoding)
    lateinit var job: Job

    /** Cancel was tapped: the bar goes at once, the tab's one-image guard stays until the job has really ended. */
    var cancelling: Boolean by mutableStateOf(false)
        private set

    fun cancel() {
        cancelling = true
        job.cancel()
    }
}

/** A fixed key: a second failure replaces the first instead of queuing up. */
private const val IMAGE_NOTICE_KEY = "image-insert"

/** The reason, in the app's language, short enough for one notice. Never carries a path, a name or a URI. */
fun imageFailureText(context: Context, reason: ImageFailure): String = context.getString(
    when (reason) {
        ImageFailure.UNREADABLE -> R.string.terminal_image_unreadable
        ImageFailure.TOO_LARGE -> R.string.terminal_image_too_large
        ImageFailure.CONNECTION_LOST -> R.string.terminal_image_connection_lost
        ImageFailure.PERMISSION_DENIED -> R.string.terminal_image_refused
        ImageFailure.NO_SPACE -> R.string.terminal_image_no_space
        ImageFailure.NOT_AVAILABLE -> R.string.terminal_image_not_available
        ImageFailure.SAVE_FAILED -> R.string.terminal_image_save_failed
        ImageFailure.PATH_UNSAFE -> R.string.terminal_image_path_unsafe
        ImageFailure.FOLDER_UNSAFE -> R.string.terminal_image_folder_unsafe
        ImageFailure.INSERT_REFUSED -> R.string.terminal_image_insert_refused
    },
)

/**
 * Starts one image run for tab [tabId] in [scope] (the terminal screen's: leaving the screen cancels it), or returns null
 * when that tab already has one: one image per tab at a time. The tab's entry in [runs] (what the bar shows, and the guard
 * that keeps the menu item off) is removed when the job has ended, not when it was cancelled and not when a channel it
 * abandoned has finished closing. [onOutcome] is told how it ended unless it was cancelled.
 */
fun startImageRun(
    scope: CoroutineScope,
    runs: SnapshotStateMap<Int, ImageRun>,
    tabId: Int,
    target: ImageTarget,
    encode: (track: (java.io.Closeable) -> Unit) -> EncodedImage,
    insertOn: CoroutineDispatcher = Dispatchers.Main.immediate,
    onOutcome: (ImageInsertOutcome) -> Unit,
): ImageRun? {
    if (runs[tabId] != null) return null
    val run = ImageRun()
    runs[tabId] = run
    run.job = scope.launch {
        val outcome = ImageInsertJob(target, encode, { run.state = it }, insertOn = insertOn).run()
        // Whoever reports the outcome must not be able to take the screen's scope down with it.
        try {
            onOutcome(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("ImageInsert", "outcome not shown: ${e.javaClass.simpleName}")
        }
    }
    // On completion of the job however it ends, also cancelled before its body ever started: the tab's guard cannot leak.
    run.job.invokeOnCompletion { if (runs[tabId] === run) runs.remove(tabId) }
    return run
}

/** A tab that is no longer shown (closed, also with its Undo pending; its tunnel gone) takes its image run with it. */
fun cancelRunsOfGoneTabs(runs: SnapshotStateMap<Int, ImageRun>, shownTabs: Set<Int>) {
    runs.entries.toList().forEach { (id, run) -> if (id !in shownTabs) run.cancel() }
}

/**
 * Starts sending [uri] to [session]'s tab, as that tab is now (see [startImageRun]). [visible] says the tab is still one
 * the owner sees (not closed with its Undo pending, not replaced): checked at the start and again just before the path is
 * pasted.
 */
fun startImageInsert(
    scope: CoroutineScope,
    context: Context,
    session: SshSession,
    uri: Uri,
    runs: SnapshotStateMap<Int, ImageRun>,
    visible: () -> Boolean = { true },
) {
    if (runs[session.id] != null) return
    val target = session.captureImageTarget()?.onlyWhile(visible) ?: return context.notice(context.getString(R.string.terminal_not_open), NoticeKind.WARN)
    val resolver = context.contentResolver
    startImageRun(scope, runs, session.id, target, { track -> ImageEncoder.encode(resolver, uri, track) }) { outcome ->
        when (outcome) {
            ImageInsertOutcome.Inserted -> context.notice(context.getString(R.string.terminal_image_done), NoticeKind.DONE, key = IMAGE_NOTICE_KEY)
            is ImageInsertOutcome.Failed -> context.notice(imageFailureText(context, outcome.reason), NoticeKind.WARN, key = IMAGE_NOTICE_KEY)
        }
    }
}

/** The first picture on the clipboard, read now (after the owner confirmed), or null. */
fun clipboardImageUri(context: Context): Uri? = try {
    context.getSystemService(ClipboardManager::class.java).primaryClip?.let { clip ->
        (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
    }
} catch (e: RuntimeException) {
    null
}

/** ⋮ → Insert image; off unless the tab is open and has no image on its way. */
@Composable
fun InsertImageMenuItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.terminal_image_insert)) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** "Preparing image…" / "Uploading image… 40%" with Cancel, between the terminal and the snippets. */
@Composable
fun ImageProgressBar(state: ImageInsertState, onCancel: () -> Unit, modifier: Modifier = Modifier, keysShown: Boolean = true) {
    val colors = AppTheme.colors
    val text = when (state) {
        // Whole tens only: the words change a few times, not with every packet.
        is ImageInsertState.Uploading -> stringResource(R.string.terminal_image_uploading, if (state.total > 0) (state.sent * 100 / state.total).toInt() / 10 * 10 else 0)
        ImageInsertState.Encoding -> stringResource(R.string.terminal_image_preparing)
    }
    BoxWithConstraints(modifier.fillMaxWidth().background(colors.termBar)) {
        // Above the main row, the keys' disclosure target owns the end of the panel (terminal-panel-contract.md, "Shared
        // geometry"): Cancel stays left of it, by the same geometry the target is placed with, so neither covers the other
        // in any orientation or font scale, and the words end before Cancel.
        // With no keys under the bar (the text editor has no disclosure) nothing owns the end of the panel.
        val reserve = if (keysShown) disclosureEndReserve(maxWidth - PANEL_SIDE_PADDING * 2) + 4.dp else 4.dp
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = reserve), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    fontSize = 13.sp,
                    color = colors.onTerm,
                    maxLines = 3,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
                    Text(stringResource(R.string.cancel), fontSize = 14.sp)
                }
            }
            if (state is ImageInsertState.Uploading && state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.sent.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.accent,
                    trackColor = colors.termKey,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.termKey)
            }
        }
    }
}

/** The clipboard holds a picture: send it to the device? ✕ or a swipe down leaves it. */
@Composable
fun ImageOfferSheet(onInsert: () -> Unit, onDismiss: () -> Unit) {
    AppSheet(
        title = stringResource(R.string.terminal_image_offer_title),
        subtitle = stringResource(R.string.terminal_image_offer_text),
        onDismiss = onDismiss,
    ) {
        Column {
            MainButton(
                text = stringResource(R.string.terminal_image_insert),
                busy = false,
                onClick = {
                    onInsert()
                    onDismiss()
                },
            )
        }
    }
}
