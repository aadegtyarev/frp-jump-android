package ru.adsrv.frpjump.ui.mqtt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.api.relativeAge
import ru.adsrv.frpjump.mqtt.FilterProblem
import ru.adsrv.frpjump.mqtt.FilterRefusal
import ru.adsrv.frpjump.mqtt.LastChange
import ru.adsrv.frpjump.mqtt.MAX_TOPIC_BYTES
import ru.adsrv.frpjump.mqtt.PinMask
import ru.adsrv.frpjump.mqtt.SubscriptionRow
import ru.adsrv.frpjump.mqtt.SubscriptionTarget
import ru.adsrv.frpjump.mqtt.filterHasGlobChars
import ru.adsrv.frpjump.mqtt.filterSkipsDollar
import ru.adsrv.frpjump.mqtt.maskCoveredBy
import ru.adsrv.frpjump.mqtt.pinnedMasks
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.ageText
import ru.adsrv.frpjump.ui.devices.OpenRow
import ru.adsrv.frpjump.ui.devices.SwipeReveal
import ru.adsrv.frpjump.ui.devices.closesOpenRow
import ru.adsrv.frpjump.ui.devices.touchesRow
import java.time.Duration

/** How far a row slides aside for its Unpin button, as on the Devices screen. */
private val REVEAL_WIDTH = 76.dp

/**
 * The Subscriptions tab: the connection's pins in their order, each with
 * a single topic's value or a wildcard filter's count of seen topics, and
 * the age of the newest change. A tap goes where the filter points; a
 * swipe to the left, "⋮" and TalkBack's actions unpin it (Undo on the
 * shared notice); a pin a card can name changes its mask in "⋮". [open]:
 * the session can still subscribe; unpinning works either way.
 */
@Composable
internal fun SubscriptionsList(
    state: MqttUiState,
    open: Boolean,
    padding: PaddingValues,
    onTap: (SubscriptionRow) -> Unit,
    onUnpin: (String) -> Unit,
    onChangeMask: (SubscriptionRow) -> Unit,
) {
    val colors = AppTheme.colors
    val openRow = remember { OpenRow() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().closesOpenRow(openRow),
        // Room under the last row for the "+" button.
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
    ) {
        if (state.pins.isEmpty()) item(key = "empty") { EmptySubscriptions() }
        items(state.subscriptions, key = { it.filter }) { row ->
            val swiped = openRow.name == row.filter
            SwipeReveal(
                open = swiped,
                enabled = true,
                onOpenChange = { now -> openRow.reported(row.filter, now) },
                actionWidth = REVEAL_WIDTH,
                action = {
                    UnpinButton(row.filter) {
                        openRow.close()
                        onUnpin(row.filter)
                    }
                },
                modifier = Modifier.touchesRow(openRow, row.filter),
            ) {
                SubscriptionRowView(
                    row,
                    now = state.now,
                    open = open,
                    // Swiped aside: a tap puts it back; closed, it goes where the filter points.
                    onClick = { if (swiped) openRow.close() else onTap(row) },
                    onUnpin = { onUnpin(row.filter) },
                    onChangeMask = { onChangeMask(row) },
                )
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 70.dp))
        }
    }
}

/** What a subscription is and the three ways to make one, so the empty tab is no dead end. */
@Composable
private fun EmptySubscriptions() {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.mqtt_subs_empty_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text(stringResource(R.string.mqtt_subs_empty_text), fontSize = 14.sp, color = colors.muted, textAlign = TextAlign.Center)
    }
}

/** The Unpin button behind a row swiped aside. */
@Composable
private fun UnpinButton(filter: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.mqtt_unpin_filter, filter)
    Surface(
        onClick = onClick,
        color = colors.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .width(REVEAL_WIDTH - 6.dp)
            .fillMaxHeight()
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = colors.error, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * One pin: the filter, then a single topic's value or a wildcard filter's
 * count, then the age of the newest change ("before connecting" as in the
 * tree); "⋮" with Change mask… and Unpin, also offered to TalkBack as
 * actions of the row, since the swipe is not.
 */
@Composable
private fun SubscriptionRowView(
    row: SubscriptionRow,
    now: Long,
    open: Boolean,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    onChangeMask: () -> Unit,
) {
    val colors = AppTheme.colors
    var menu by remember { mutableStateOf(false) }
    val unpinLabel = stringResource(R.string.mqtt_unpin_filter, row.filter)
    val maskLabel = stringResource(R.string.mqtt_change_mask)
    Row(
        Modifier
            .fillMaxWidth()
            // Opaque: the Unpin button behind shows only where the row slid away.
            .background(colors.ground)
            .clickable(role = Role.Button, onClickLabel = stringResource(tapLabel(row.target)), onClick = onClick)
            .semantics {
                customActions = buildList {
                    add(CustomAccessibilityAction(unpinLabel) { onUnpin(); true })
                    if (row.rooted != null && open) add(CustomAccessibilityAction(maskLabel) { onChangeMask(); true })
                }
            }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KindTile(branch = !row.single)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.filter, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val value = row.node?.value
            when {
                value != null -> ValueText(value)
                row.single -> Text(stringResource(R.string.mqtt_no_messages), fontSize = 13.sp, color = colors.muted)
                row.topics > 0 -> Text(pluralStringResource(R.plurals.mqtt_topics, row.topics, count(row.topics)), fontSize = 13.sp, color = colors.ink)
                else -> Text(stringResource(R.string.mqtt_subs_no_topics), fontSize = 13.sp, color = colors.muted)
            }
            when (val change = row.change) {
                is LastChange.At -> Text(ageText(relativeAge(Duration.ofMillis(maxOf(0, now - change.ms)))), fontSize = 12.sp, color = colors.muted)
                LastChange.BeforeSession -> Text(stringResource(R.string.mqtt_before_session), fontSize = 12.sp, color = colors.muted)
                null -> Unit
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mqtt_subscription_actions, row.filter), tint = colors.muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (row.rooted != null) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(maskLabel)
                                // Off with why, rather than gone.
                                if (!open) Text(stringResource(R.string.mqtt_closed), fontSize = 12.sp, color = colors.muted)
                            }
                        },
                        enabled = open,
                        onClick = {
                            menu = false
                            onChangeMask()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mqtt_unpin)) },
                    onClick = {
                        menu = false
                        onUnpin()
                    },
                )
            }
        }
    }
}

/** What a tap on a row does, as TalkBack says it. */
private fun tapLabel(target: SubscriptionTarget): Int = when (target) {
    is SubscriptionTarget.Topic -> R.string.mqtt_open_card
    is SubscriptionTarget.Branch -> R.string.mqtt_open_in_tree
    is SubscriptionTarget.Search -> R.string.mqtt_search
}

/** "Change mask…" of a pin a card can name: the Pin sheet, whose pick replaces the pin. */
@Composable
internal fun ChangeMaskSheet(row: SubscriptionRow, pins: List<String>, onPick: (PinMask) -> Unit, onDismiss: () -> Unit) {
    val rooted = row.rooted ?: return
    PinSheet(
        path = rooted.topic,
        pinned = pinnedMasks(rooted.topic, pins),
        // The pin being replaced covers its narrower masks itself: it does not count against them.
        coveredBy = maskCoveredBy(rooted.topic, pins - row.filter),
        initial = rooted.mask,
        onPick = onPick,
        onDismiss = onDismiss,
        title = stringResource(R.string.mqtt_change_mask_title),
        subtitle = stringResource(R.string.mqtt_change_mask_subtitle, row.filter),
    )
}

/**
 * "+": a topic filter typed or pasted, taken as is, checked as the
 * contract's Pins say; [onSubscribe] answers why it cannot be pinned, or
 * null, and then the dialog is closed by its caller. A filter starting
 * with a wildcard is reminded that it skips `$` topics; `*` and `?` that
 * they are plain characters here.
 */
@Composable
internal fun SubscribeDialog(onSubscribe: (String) -> FilterRefusal?, onDismiss: () -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    var refusal by remember { mutableStateOf<FilterRefusal?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val go = { if (text.isNotEmpty()) refusal = onSubscribe(text) }
    val notes = buildList {
        if (filterSkipsDollar(text)) add(stringResource(R.string.mqtt_filter_dollar))
        if (filterHasGlobChars(text)) add(stringResource(R.string.mqtt_filter_literal))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mqtt_subs_add)) },
        text = {
            Field(
                label = stringResource(R.string.mqtt_filter),
                value = field,
                onValueChange = {
                    if (it.text != field.text) refusal = null
                    field = it
                },
                mono = true,
                keyboard = KeyboardType.Uri,
                focusRequester = focus,
                problem = refusal?.let { refusalText(it) },
                note = notes.joinToString("\n").ifEmpty { null },
                onDone = go,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty(), onClick = go) { Text(stringResource(R.string.mqtt_pin)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun refusalText(refusal: FilterRefusal): String = when (refusal) {
    is FilterRefusal.Invalid -> when (refusal.problem) {
        FilterProblem.EMPTY -> stringResource(R.string.mqtt_filter_empty)
        FilterProblem.WILDCARD_IN_LEVEL -> stringResource(R.string.mqtt_filter_in_level)
        FilterProblem.HASH_NOT_LAST -> stringResource(R.string.mqtt_pattern_hash_last)
        FilterProblem.NUL -> stringResource(R.string.mqtt_topic_nul)
        FilterProblem.TOO_LONG -> stringResource(R.string.mqtt_topic_too_long, MAX_TOPIC_BYTES)
    }
    FilterRefusal.Pinned -> stringResource(R.string.mqtt_filter_already)
    is FilterRefusal.Covered -> stringResource(R.string.mqtt_filter_pinned, refusal.by)
    FilterRefusal.SessionClosed -> stringResource(R.string.mqtt_closed)
}
