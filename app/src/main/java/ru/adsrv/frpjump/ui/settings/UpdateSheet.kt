package ru.adsrv.frpjump.ui.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.MainButton
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.update.ApkInstaller
import ru.adsrv.frpjump.update.Release
import ru.adsrv.frpjump.update.UpdateDownload
import java.io.File

/**
 * "Update to x.y.z": the release notes, then "Download and install". The
 * download's progress shows in place of the button; once its SHA-256 is
 * checked the APK goes to the system installer, which asks the owner. If
 * Android does not yet let this app install apps, a dialog says why and
 * opens that switch; back from it with the switch on, installing goes on.
 */
@Composable
fun UpdateSheet(
    release: Release,
    download: UpdateDownload,
    onDownload: () -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    // The owner tapped "Download and install": the installer opens once the download is ready.
    var installWhenReady by rememberSaveable { mutableStateOf(false) }
    var askPermission by rememberSaveable { mutableStateOf(false) }
    var waitingPermission by rememberSaveable { mutableStateOf(false) }
    var notAllowed by rememberSaveable { mutableStateOf(false) }

    val install = { apk: File ->
        notAllowed = false
        if (ApkInstaller.allowed(context)) {
            if (!ApkInstaller.install(context, apk)) toast(context, R.string.update_no_installer)
        } else {
            askPermission = true
        }
    }
    LaunchedEffect(download) {
        if (download is UpdateDownload.Ready && installWhenReady) {
            installWhenReady = false
            install(download.apk)
        }
        if (download is UpdateDownload.Failed) installWhenReady = false
    }
    // Back from the system's "Install unknown apps" screen.
    val latestDownload by rememberUpdatedState(download)
    LifecycleResumeEffect(Unit) {
        if (waitingPermission) {
            waitingPermission = false
            val apk = (latestDownload as? UpdateDownload.Ready)?.apk
            if (ApkInstaller.allowed(context)) apk?.let(install) else notAllowed = true
        }
        onPauseOrDispose { }
    }

    AppSheet(
        title = stringResource(R.string.update_title, release.version),
        subtitle = release.apkSize.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
        onDismiss = onDismiss,
    ) {
        if (release.notes.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.update_notes), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Surface(color = colors.tile, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(release.notes, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        when {
            release.checksumUrl == null -> Text(stringResource(R.string.update_no_checksum), color = colors.error, fontSize = 14.sp)
            download is UpdateDownload.Running -> DownloadProgress(context, download)
            download is UpdateDownload.Ready -> {
                if (notAllowed) Text(stringResource(R.string.update_not_allowed), color = colors.warning, fontSize = 14.sp)
                MainButton(text = stringResource(R.string.update_install), busy = false, onClick = { install(download.apk) })
            }
            else -> {
                (download as? UpdateDownload.Failed)?.let { Text(it.message, color = colors.error, fontSize = 14.sp) }
                MainButton(
                    text = stringResource(if (download is UpdateDownload.Failed) R.string.update_retry else R.string.update_download_install),
                    busy = false,
                    onClick = {
                        onClearError()
                        installWhenReady = true
                        onDownload()
                    },
                )
            }
        }
    }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text(stringResource(R.string.update_allow_title)) },
            text = { Text(stringResource(R.string.update_allow_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    waitingPermission = ApkInstaller.openPermission(context)
                    if (!waitingPermission) notAllowed = true
                }) { Text(stringResource(R.string.lock_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A bar and "4.1 MB of 21 MB", where the button was. */
@Composable
private fun DownloadProgress(context: Context, download: UpdateDownload.Running) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        if (download.total > 0) {
            LinearProgressIndicator(
                progress = { (download.read.toFloat() / download.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    R.string.update_progress,
                    Formatter.formatShortFileSize(context, download.read),
                    Formatter.formatShortFileSize(context, download.total),
                ),
                fontSize = 13.sp,
                color = AppTheme.colors.muted,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun toast(context: Context, text: Int) = context.notice(context.getString(text), NoticeKind.WARN, long = true)
