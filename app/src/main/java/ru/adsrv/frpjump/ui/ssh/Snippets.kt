package ru.adsrv.frpjump.ui.ssh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.snippets.MAX_SNIPPET_LENGTH
import ru.adsrv.frpjump.snippets.Snippet
import ru.adsrv.frpjump.snippets.snippetText
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.MainButton

/** What the snippets row and its manager may do with the store (each call writes it off the main thread). */
class SnippetActions(
    val add: (String) -> Unit,
    val update: (id: String, text: String) -> Unit,
    val remove: (id: String) -> Unit,
    val move: (id: String, by: Int) -> Unit,
)

/**
 * The saved commands above the extra keys. Tap types a command, long
 * press types it and presses Enter; the last chip ("+ Add command" while
 * there are none) opens the manager, which says so.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnippetsRow(snippets: List<Snippet>, onSend: (text: String, run: Boolean) -> Unit, onManage: () -> Unit) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    val runLabel = stringResource(R.string.snippet_run)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.termBar)
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        snippets.forEach { snippet ->
            Chip(
                Modifier.combinedClickable(
                    onClickLabel = stringResource(R.string.snippet_type),
                    onLongClickLabel = runLabel,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend(snippet.text, true)
                    },
                    onClick = { onSend(snippet.text, false) },
                ),
            ) {
                Text(
                    snippet.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colors.onTerm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
        Chip(Modifier.clickable(onClick = onManage)) {
            Text(
                if (snippets.isEmpty()) stringResource(R.string.snippet_add_first) else "+",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colors.termMuted,
            )
        }
    }
}

@Composable
private fun Chip(action: Modifier, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .border(1.dp, colors.termLine, shape)
            .then(action)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** The manager: the list in its order with ↑ ↓, a tap to change or delete one, "Add command". */
@Composable
fun SnippetsSheet(snippets: List<Snippet>, actions: SnippetActions, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    // "" is a new one; an id is that snippet.
    var editing by rememberSaveable { mutableStateOf<String?>(if (snippets.isEmpty()) "" else null) }
    AppSheet(title = stringResource(R.string.snippets), subtitle = stringResource(R.string.snippets_hint), onDismiss = onDismiss) {
        if (snippets.isEmpty()) {
            Text(stringResource(R.string.snippets_empty), fontSize = 14.sp, color = colors.muted)
        }
        Column {
            snippets.forEachIndexed { index, snippet ->
                if (index > 0) HorizontalDivider(color = colors.divider)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { editing = snippet.id }
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        snippet.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { actions.move(snippet.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.snippet_move_up))
                    }
                    IconButton(onClick = { actions.move(snippet.id, 1) }, enabled = index < snippets.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.snippet_move_down))
                    }
                }
            }
        }
        MainButton(text = stringResource(R.string.snippet_add), busy = false, onClick = { editing = "" })
    }
    editing?.let { id ->
        val snippet = snippets.firstOrNull { it.id == id }
        SnippetDialog(
            initial = snippet?.text.orEmpty(),
            isNew = snippet == null,
            onSave = { text ->
                if (snippet == null) actions.add(text) else actions.update(snippet.id, text)
                editing = null
            },
            onDelete = {
                snippet?.let { actions.remove(it.id) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SnippetDialog(initial: String, isNew: Boolean, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    // The keyboard comes up with the dialog: the command is what it is opened for.
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.snippet_add else R.string.snippet_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    label = stringResource(R.string.snippet_command),
                    value = text,
                    onValueChange = { text = it.take(MAX_SNIPPET_LENGTH) },
                    mono = true,
                    placeholder = "df -h",
                    focusRequester = focus,
                )
                if (!isNew) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.snippet_delete), color = AppTheme.colors.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = snippetText(text) != null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
