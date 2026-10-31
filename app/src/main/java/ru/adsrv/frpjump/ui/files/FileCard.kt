package ru.adsrv.frpjump.ui.files

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.EntryType
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.octalMode
import ru.adsrv.frpjump.files.permissionString
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.NoticeLayer
import ru.adsrv.frpjump.ui.DownloadGlyph
import ru.adsrv.frpjump.ui.ShareGlyph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What a file card does with its entry. */
class CardActions(
    /** A folder: open it; a file: the text viewer, or "Open with". */
    val open: (RemoteEntry) -> Unit,
    val download: (RemoteEntry) -> Unit,
    val share: (RemoteEntry) -> Unit,
    val goToTarget: (RemoteEntry) -> Unit,
    val retry: (RemoteEntry) -> Unit,
    val dismissFailure: (RemoteEntry) -> Unit,
    val cancel: (RemoteEntry) -> Unit,
)

/**
 * The card of a file or folder, as the app's bottom sheets: name, size,
 * modified, permissions, owner, a link's target; Open, and for files
 * Download and Share. Nothing is transferred until one is tapped; a
 * transfer of it shows here with its bar and Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileCard(entry: RemoteEntry, transfer: Transfer?, online: Boolean, actions: CardActions, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        // A message posted while the sheet is open shows on it, not under its scrim.
        NoticeLayer {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    EntryIcon(entry)
                    Text(entry.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, modifier = Modifier.weight(1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoLine(stringResource(R.string.files_path), entry.path, mono = true)
                    entry.link?.let { link ->
                        InfoLine(
                            stringResource(R.string.files_card_link),
                            link.path,
                            mono = true,
                            problem = stringResource(R.string.files_broken).takeIf { link.broken },
                        )
                    }
                    if (entry.type == EntryType.FILE) {
                        InfoLine(stringResource(R.string.files_card_size), Formatter.formatFileSize(context, entry.size))
                    }
                    if (entry.modified > 0 && entry.link?.broken != true) {
                        InfoLine(stringResource(R.string.files_card_modified), fullTime(Instant.ofEpochSecond(entry.modified)))
                    }
                    entry.mode?.let { InfoLine(stringResource(R.string.files_card_permissions), "${permissionString(it)}  ${octalMode(it)}", mono = true) }
                    if (entry.uid != null && entry.gid != null) {
                        InfoLine(stringResource(R.string.files_card_owner), stringResource(R.string.files_card_owner_ids, entry.uid, entry.gid))
                    }
                }
                transfer?.let {
                    TransferLine(it, onRetry = { actions.retry(entry) }, onDismiss = { actions.dismissFailure(entry) }, onCancel = { actions.cancel(entry) })
                }
                CardButtons(entry, transfer, online, actions, onDismiss)
            }
        }
    }
}

@Composable
private fun CardButtons(entry: RemoteEntry, transfer: Transfer?, online: Boolean, actions: CardActions, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val idle = transfer == null || transfer.failure != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            entry.isDir -> PrimaryButton(stringResource(R.string.files_open), enabled = true) {
                onDismiss()
                actions.open(entry)
            }
            entry.transferable -> {
                if (!online) Text(stringResource(R.string.files_card_offline), fontSize = 13.sp, color = colors.muted)
                PrimaryButton(stringResource(R.string.files_open), enabled = online && idle) { actions.open(entry) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.files_download), DownloadGlyph, online && idle, Modifier.weight(1f)) { actions.download(entry) }
                    SecondaryButton(stringResource(R.string.files_share), ShareGlyph, online && idle, Modifier.weight(1f)) { actions.share(entry) }
                }
            }
            // A broken link, a device or a socket: nothing to transfer, and the card says why.
            else -> Text(
                if (entry.link?.broken == true) stringResource(R.string.files_card_broken) else stringResource(R.string.files_not_regular, entry.name),
                fontSize = 13.sp,
                color = colors.muted,
            )
        }
        if (entry.link != null) {
            SecondaryButton(stringResource(R.string.files_go_to_target), null, enabled = true, Modifier.fillMaxWidth()) {
                onDismiss()
                actions.goToTarget(entry)
            }
        }
    }
}

/** The sheet's main button, as "Save" of "New connection". */
@Composable
internal fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, disabledContainerColor = colors.tile, disabledContentColor = colors.muted),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun SecondaryButton(text: String, icon: ImageVector?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) colors.accent else colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.card, contentColor = colors.accent),
        modifier = modifier.height(48.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A muted label over its value; [problem] after it in the error colour. */
@Composable
internal fun InfoLine(label: String, value: String, mono: Boolean = false, problem: String? = null) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = colors.muted)
        Text(value, fontSize = 14.sp, color = colors.ink, fontFamily = if (mono) FontFamily.Monospace else null)
        problem?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.error) }
    }
}

/** The date and time in full, in the app's language and the phone's zone. */
@Composable
internal fun fullTime(at: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale) }
    return format.format(at.atZone(ZoneId.systemDefault()))
}
