package ru.adsrv.frpjump.ui.files

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.EntryType
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.linkLabel
import ru.adsrv.frpjump.files.pathSegments
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FileGlyph
import ru.adsrv.frpjump.ui.FolderGlyph
import ru.adsrv.frpjump.ui.LinkGlyph
import ru.adsrv.frpjump.ui.OtherFileGlyph
import ru.adsrv.frpjump.ui.shortTime
import java.time.Instant

/**
 * The folder shown, as parts: a tap on a part opens that folder, a tap on
 * the last one (or the empty end of the bar) lets the owner type a path.
 */
@Composable
fun PathBar(path: String?, onOpen: (String) -> Unit, onType: () -> Unit) {
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    // The deepest part stays in view.
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.files_go_to_folder), onClick = onType)
            .heightIn(min = 40.dp)
            .padding(horizontal = 4.dp)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val segments = path?.let(::pathSegments).orEmpty()
        segments.forEachIndexed { i, segment ->
            val last = i == segments.lastIndex
            if (i > 1) Text("/", color = colors.faint, fontSize = 14.sp)
            Text(
                segment.label,
                fontSize = 14.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) colors.ink else colors.accent,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { if (last) onType() else onOpen(segment.path) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

/** What a row's long press offers. */
class EntryActions(
    val open: (RemoteEntry) -> Unit,
    val download: (RemoteEntry) -> Unit,
    val share: (RemoteEntry) -> Unit,
    val goToTarget: (RemoteEntry) -> Unit,
)

/**
 * A folder or file: icon by type (a link badge on symlinks), name, size and
 * time, a link's target; a bar while it is being transferred. Long press:
 * Download / Share (files), Open (folders), Go to the target (links).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    entry: RemoteEntry,
    transfer: Transfer?,
    online: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onTap: () -> Unit,
    actions: EntryActions,
    /** Retry and dismiss of a failed transfer of this row, cancel of a running one. */
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val broken = entry.link?.broken == true
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = { onMenu(true) })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EntryIcon(entry)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.name, fontSize = 15.sp, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    if (entry.type == EntryType.FILE) add(Formatter.formatShortFileSize(context, entry.size))
                    if (entry.modified > 0 && !broken) add(shortTime(Instant.ofEpochSecond(entry.modified)))
                }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = colors.muted, maxLines = 1)
                entry.link?.let { link ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            linkLabel(link),
                            fontSize = 12.sp,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (link.broken) Text(stringResource(R.string.files_broken), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1)
                    }
                }
                transfer?.let { TransferLine(it, onRetry = { onRetry() }, onDismiss = onDismiss, onCancel = onCancel) }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }, offset = DpOffset(72.dp, 0.dp)) {
            val item = @Composable { label: Int, enabled: Boolean, action: (RemoteEntry) -> Unit ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled, onClick = {
                    onMenu(false)
                    action(entry)
                })
            }
            if (entry.isDir) {
                item(R.string.files_open, true, actions.open)
            } else {
                val idle = transfer == null || transfer.failure != null
                item(R.string.files_download, online && idle, actions.download)
                item(R.string.files_share, online && idle, actions.share)
            }
            if (entry.link != null) item(R.string.files_go_to_target, true, actions.goToTarget)
        }
    }
}

/** A file being uploaded into the folder shown: name and its bar, above the list; failed, the reason and Retry. */
@Composable
fun UploadRow(transfer: Transfer, onRetry: () -> Unit, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val colors = AppTheme.colors
    val failed = transfer.failure != null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(if (failed) colors.errorContainer else colors.accentContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FileGlyph, contentDescription = null, tint = if (failed) colors.onErrorContainer else colors.onAccentContainer, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(if (failed) R.string.files_not_uploaded else R.string.files_uploading, transfer.name),
                fontSize = 15.sp,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TransferLine(transfer, onRetry, onDismiss, onCancel)
        }
    }
}

/**
 * A transfer's bar with ✕ to cancel just this one; failed, its reason in
 * plain words with Retry and Dismiss, where it was started.
 */
@Composable
private fun TransferLine(transfer: Transfer, onRetry: () -> Unit, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val colors = AppTheme.colors
    val failure = transfer.failure
    if (failure != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(failure, fontSize = 12.sp, color = colors.error, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = colors.accent) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.files_dismiss), color = colors.muted) }
        }
        return
    }
    val fraction = transfer.fraction
    Row(verticalAlignment = Alignment.CenterVertically) {
        val bar = Modifier.weight(1f)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, color = colors.accent, trackColor = colors.tile, modifier = bar)
        } else {
            LinearProgressIndicator(color = colors.accent, trackColor = colors.tile, modifier = bar)
        }
        val label = stringResource(R.string.files_cancel_transfer)
        Box(
            Modifier
                .size(width = 48.dp, height = 24.dp)
                // A 48 dp target reaching into the row's padding: the line stays as tall as the icon.
                .requiredSize(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onCancel)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** Folder, file or other on a tile; a symlink gets a small link badge, a broken one in the error colour. */
@Composable
private fun EntryIcon(entry: RemoteEntry) {
    val colors = AppTheme.colors
    val broken = entry.link?.broken == true
    Box {
        Box(
            Modifier
                .size(40.dp)
                .background(if (entry.isDir) colors.accentContainer else colors.tile, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (entry.type) {
                    EntryType.DIR -> FolderGlyph
                    EntryType.FILE -> FileGlyph
                    EntryType.OTHER -> OtherFileGlyph
                },
                contentDescription = null,
                tint = when {
                    broken -> colors.error
                    entry.isDir -> colors.onAccentContainer
                    else -> colors.muted
                },
                modifier = Modifier.size(22.dp),
            )
        }
        if (entry.link != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(18.dp)
                    .background(colors.card, CircleShape)
                    .border(1.dp, colors.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LinkGlyph, contentDescription = stringResource(R.string.files_link_badge), tint = if (broken) colors.error else colors.accent, modifier = Modifier.size(12.dp))
            }
        }
    }
}
