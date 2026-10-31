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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.EntryType
import ru.adsrv.frpjump.files.FileKind
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.fileKind
import ru.adsrv.frpjump.files.linkLabel
import ru.adsrv.frpjump.files.pathSegments
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.ArchiveGlyph
import ru.adsrv.frpjump.ui.BrokenLinkGlyph
import ru.adsrv.frpjump.ui.ExecutableGlyph
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
    val segments = path?.let(::pathSegments).orEmpty()
    PathBar(segments.map { it.label }, onOpen = { onOpen(segments[it].path) }, onType = onType, typeLabel = stringResource(R.string.files_go_to_folder))
}

/**
 * A place as parts (Files: folders; MQTT: topic levels), the first being
 * the root (Files: `/`; MQTT: `#`, read out as "Root"): a tap on part `i` opens it ([onOpen] gets `i`), a tap on
 * the last one (or the empty end of the bar) lets the owner type a path
 * ([onType], read out as [typeLabel]).
 */
@Composable
fun PathBar(
    labels: List<String>,
    onOpen: (Int) -> Unit,
    onType: () -> Unit,
    typeLabel: String,
    /** Whether a `/` goes before part `i`; between parts after the root by default. */
    separatorBefore: (Int) -> Boolean = { it > 1 },
    /** Drawn for the root in place of its label (read out as "Root" either way). */
    rootIcon: ImageVector? = null,
    /** What part `i` is read out as when its label alone says too little; null: the label. */
    spoken: (Int) -> String? = { null },
) {
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    // The deepest part stays in view.
    LaunchedEffect(labels) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = typeLabel, onClick = onType)
            .heightIn(min = 40.dp)
            .padding(horizontal = 4.dp)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val rootSpoken = stringResource(R.string.path_root)
        labels.forEachIndexed { i, label ->
            val last = i == labels.lastIndex
            if (separatorBefore(i)) Text("/", color = colors.faint, fontSize = 14.sp)
            val said = if (i == 0) rootSpoken else spoken(i)
            val partModifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { if (last) onType() else onOpen(i) }
                // "/" alone reads as nothing.
                .then(if (said != null) Modifier.semantics { contentDescription = said } else Modifier)
                .padding(horizontal = 6.dp, vertical = 8.dp)
            if (i == 0 && rootIcon != null) {
                Icon(rootIcon, contentDescription = null, tint = if (last) colors.ink else colors.accent, modifier = partModifier.size(18.dp))
            } else {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (last) colors.ink else colors.accent,
                    maxLines = 1,
                    modifier = partModifier,
                )
            }
        }
    }
}

/**
 * A folder or file: icon by type (a link badge on symlinks), name, size and
 * time, a link's target; a bar while it is being transferred; a small "⋮"
 * at the end for its card. While selecting ([selecting]), a checkbox takes
 * the "⋮"'s place and a tap picks the row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    entry: RemoteEntry,
    transfer: Transfer?,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDetails: () -> Unit,
    /** Retry and dismiss of a failed transfer of this row, cancel of a running one. */
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val broken = entry.link?.broken == true
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentContainer.copy(alpha = 0.55f) else Color.Transparent)
            // Read out as picked while selecting.
            .then(if (selecting) Modifier.semantics { this.selected = selected } else Modifier)
            .combinedClickable(
                onClickLabel = if (selecting) null else stringResource(if (entry.isDir) R.string.files_open else R.string.files_details_short),
                onLongClickLabel = stringResource(R.string.files_select),
                onClick = onTap,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
        if (selecting) {
            // The row's tap does it; the box only shows it.
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(width = 36.dp, height = 44.dp))
        } else {
            IconButton(onClick = onDetails, modifier = Modifier.size(width = 36.dp, height = 44.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.files_details, entry.name), tint = colors.muted)
            }
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
fun TransferLine(transfer: Transfer, onRetry: () -> Unit, onDismiss: () -> Unit, onCancel: () -> Unit) {
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
        if (transfer.waiting) {
            // Behind the two going: it starts by itself; ✕ drops it.
            Text(stringResource(R.string.files_waiting), fontSize = 12.sp, color = colors.muted, modifier = bar)
        } else if (fraction != null) {
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

/**
 * The entry's kind on a tile, by glyph and a muted tint ([fileKind]): a
 * folder on the accent's tile, a file, an executable, an archive, a special
 * file; a symlink gets a small link badge over its target's kind, a broken
 * one a broken chain in the error colour instead.
 */
@Composable
fun EntryIcon(entry: RemoteEntry) {
    val colors = AppTheme.colors
    val kind = fileKind(entry)
    Box {
        Box(
            Modifier
                .size(40.dp)
                .background(if (kind == FileKind.FOLDER) colors.accentContainer else colors.tile, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (kind) {
                    FileKind.FOLDER -> FolderGlyph
                    FileKind.FILE -> FileGlyph
                    FileKind.EXECUTABLE -> ExecutableGlyph
                    FileKind.ARCHIVE -> ArchiveGlyph
                    FileKind.SPECIAL -> OtherFileGlyph
                    FileKind.BROKEN_LINK -> BrokenLinkGlyph
                },
                contentDescription = stringResource(
                    when (kind) {
                        FileKind.FOLDER -> R.string.files_kind_folder
                        FileKind.FILE -> R.string.files_kind_file
                        FileKind.EXECUTABLE -> R.string.files_kind_executable
                        FileKind.ARCHIVE -> R.string.files_kind_archive
                        FileKind.SPECIAL -> R.string.files_kind_special
                        FileKind.BROKEN_LINK -> R.string.files_kind_broken_link
                    },
                ),
                tint = when (kind) {
                    FileKind.FOLDER -> colors.onAccentContainer
                    FileKind.FILE, FileKind.SPECIAL -> colors.muted
                    FileKind.EXECUTABLE -> colors.executable
                    FileKind.ARCHIVE -> colors.archive
                    FileKind.BROKEN_LINK -> colors.error
                },
                modifier = Modifier.size(22.dp),
            )
        }
        if (entry.link != null && kind != FileKind.BROKEN_LINK) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(18.dp)
                    .background(colors.card, CircleShape)
                    .border(1.dp, colors.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LinkGlyph, contentDescription = stringResource(R.string.files_link_badge), tint = colors.accent, modifier = Modifier.size(12.dp))
            }
        }
    }
}
