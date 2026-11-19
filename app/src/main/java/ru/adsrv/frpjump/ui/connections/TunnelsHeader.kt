package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.LocalAppLocked
import ru.adsrv.frpjump.ui.PowerGlyph
import ru.adsrv.frpjump.ui.SearchGlyph

const val TUNNELS_HEADING_TAG = "tunnels_heading"
const val TUNNELS_SEARCH_TAG = "tunnels_search"
const val TUNNELS_SEARCH_FIELD_TAG = "tunnels_search_field"
const val TUNNELS_SEARCH_BACK_TAG = "tunnels_search_back"
const val TUNNELS_SEARCH_CLEAR_TAG = "tunnels_search_clear"
const val TUNNELS_FILTER_TAG = "tunnels_filter"
const val TUNNELS_STOP_ALL_TAG = "tunnels_stop_all"
const val TUNNELS_CAPSULE_TAG = "tunnels_capsule"

/**
 * The room the collapsed search keeps in the header's one row: 130 dp at 100% font, growing with the size body text really has (system
 * font scaling is not linear, so the measured size of 16 sp is used, not the scale), so a large font wraps the header.
 */
private const val SEARCH_ROOM_DP = 130

/**
 * The Tunnels tab's header, with no visible title (the bottom bar names the tab; TalkBack gets an invisible heading).
 *
 * Collapsed: one row, [search pill][capsule], the capsule being "● N | ⏻" (see [OnCapsule]). When they do not fit (a large font in a
 * narrow window; decided by measuring) two rows: search, then the capsule. Expanded ([searching]): the field takes the whole header, with ← (close) and ✕ (clear).
 *
 * [filterOn] is what the filter does now (the choice, and something is on); [count] is the tunnels that are on. The whole capsule is disabled
 * at 0, in its place, so the header never moves.
 */
@Composable
fun TunnelsHeader(
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    focusOnOpen: Boolean,
    onFocused: () -> Unit,
    scopeOn: Boolean,
    filterOn: Boolean,
    count: Int,
    onFilter: (Boolean) -> Unit,
    stopBusy: Boolean,
    onStopAll: () -> Unit,
    modifier: Modifier = Modifier,
    deviceFilter: (@Composable () -> Unit)? = null,
) {
    val title = stringResource(R.string.tab_connections)
    Box(modifier.fillMaxWidth()) {
        // The heading screen readers land on: invisible and tiny.
        Box(Modifier.size(1.dp).testTag(TUNNELS_HEADING_TAG).semantics(mergeDescendants = true) { heading(); contentDescription = title })
        if (searching) {
            Column {
                SearchField(query, onQuery, onCloseSearch, focusOnOpen, onFocused, scopeOn)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    deviceFilter?.invoke()
                    OnCapsule(filterOn, count, onFilter, stopBusy, onStopAll)
                }
            }
        } else {
            HeaderRows(
                search = { SearchPill(onOpenSearch) },
                capsule = { OnCapsule(filterOn, count, onFilter, stopBusy, onStopAll) },
                deviceFilter = deviceFilter,
            )
        }
    }
}

/** Lays search and the capsule on one row when there is room, otherwise reflows the capsule below. */
@Composable
private fun HeaderRows(search: @Composable () -> Unit, capsule: @Composable () -> Unit, deviceFilter: (@Composable () -> Unit)?) {
    Layout(content = { search(); capsule(); deviceFilter?.invoke() }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val capsuleItem = measurables[1].measure(Constraints(maxWidth = maxWidth))
        val filterItem = measurables.getOrNull(2)?.measure(Constraints(maxWidth = maxWidth))
        val filterWidth = filterItem?.let { it.width + gap } ?: 0
        val naturalSearch = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val searchWants = maxOf(naturalSearch, ((if (deviceFilter == null) SEARCH_ROOM_DP else 96).dp.toPx() * (16.sp.toPx() / 16.dp.toPx())).roundToInt())
        if (searchWants + filterWidth + capsuleItem.width + gap <= maxWidth) {
            val searchItem = measurables[0].measure(Constraints.fixedWidth((maxWidth - filterWidth - capsuleItem.width - gap).coerceAtLeast(0)))
            val height = maxOf(searchItem.height, capsuleItem.height, filterItem?.height ?: 0)
            layout(maxWidth, height) {
                searchItem.placeRelative(0, (height - searchItem.height) / 2)
                filterItem?.placeRelative(searchItem.width + gap, (height - filterItem.height) / 2)
                capsuleItem.placeRelative(searchItem.width + gap + filterWidth, (height - capsuleItem.height) / 2)
            }
        } else {
            val searchItem = measurables[0].measure(Constraints.fixedWidth((maxWidth - filterWidth).coerceAtLeast(0)))
            val firstHeight = maxOf(searchItem.height, filterItem?.height ?: 0)
            layout(maxWidth, firstHeight + capsuleItem.height) {
                searchItem.placeRelative(0, 0)
                filterItem?.placeRelative(searchItem.width + gap, 0)
                capsuleItem.placeRelative(0, firstHeight)
            }
        }
    }
}

/** The collapsed search: a 36 dp pill, "🔍 Search", with a touch target of 44 dp. A tap expands it. */
@Composable
private fun SearchPill(onClick: () -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.connections_search)
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .testTag(TUNNELS_SEARCH_TAG)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .fillMaxWidth()
                .background(colors.card, CircleShape)
                .border(1.dp, colors.outline, CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(SearchGlyph, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.connections_search_pill), fontSize = 15.sp, color = colors.muted, maxLines = 1)
        }
    }
}

/** The expanded search over the whole header: ←, the field (its placeholder names the scope) and ✕. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, focus: Boolean, onFocused: () -> Unit, scopeOn: Boolean) {
    val colors = AppTheme.colors
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val locked = LocalAppLocked.current
    val label = stringResource(R.string.connections_search)
    val hint = stringResource(if (scopeOn) R.string.connections_search_scope_on else R.string.connections_search_scope_all)
    // Just opened by a tap: focus, with the keyboard up; not while locked (it comes after unlock).
    LaunchedEffect(focus, locked) {
        if (!focus || locked) return@LaunchedEffect
        runCatching { requester.requestFocus() }
        keyboard?.show()
        onFocused()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.size(44.dp).testTag(TUNNELS_SEARCH_BACK_TAG)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_close), tint = colors.ink, modifier = Modifier.size(22.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .background(colors.card, CircleShape)
                .border(BorderStroke(1.dp, colors.accent), CircleShape)
                .padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(requester)
                    .testTag(TUNNELS_SEARCH_FIELD_TAG)
                    .semantics { contentDescription = "$label. $hint" },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(hint, fontSize = 16.sp, color = colors.muted, maxLines = 2)
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(40.dp).testTag(TUNNELS_SEARCH_CLEAR_TAG)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.connections_clear_query), tint = colors.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private val CAPSULE_HEIGHT = 38.dp
private val TOUCH_TARGET = 48.dp

/**
 * The capsule "● N | ⏻": one rounded container, 38 dp tall (taller only when a large font needs it), with a thin outline and a thin divider
 * between two halves; each half's touch target is at least 48 dp. All of it is disabled at 0 (a muted dot, number and ⏻), in its place.
 *
 * Left: a green dot and the count of the tunnels that are on; a tap toggles the filter "only tunnels that are on" (tonal accent fill while it
 * is on). TalkBack: "Only tunnels that are on, N", a switch with its state. Right: ⏻ in red, Stop all (a spinner while the servers are told).
 * TalkBack: "Turn off all tunnels, N".
 */
@Composable
private fun OnCapsule(on: Boolean, count: Int, onFilter: (Boolean) -> Unit, busy: Boolean, onStopAll: () -> Unit) {
    val colors = AppTheme.colors
    val enabled = count > 0
    val outline = colors.outline
    val filterDescription = stringResource(R.string.connections_only_on, count)
    val stopDescription = stringResource(R.string.disconnect_all_count, count)
    val filterOn = on && enabled
    val ink = when {
        !enabled -> colors.muted
        filterOn -> colors.onAccentContainer
        else -> colors.ink
    }
    val leftShape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50, topEndPercent = 0, bottomEndPercent = 0)
    val rightShape = RoundedCornerShape(topStartPercent = 0, bottomStartPercent = 0, topEndPercent = 50, bottomEndPercent = 50)
    val leftSource = remember { MutableInteractionSource() }
    val rightSource = remember { MutableInteractionSource() }
    // The outline is drawn over the halves, around their visible part: the touch targets are 5 dp taller than it on each side.
    val margin = (TOUCH_TARGET - CAPSULE_HEIGHT) / 2
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .testTag(TUNNELS_CAPSULE_TAG)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                val top = margin.toPx()
                val height = size.height - 2 * top
                drawRoundRect(
                    outline,
                    topLeft = Offset(stroke / 2, top + stroke / 2),
                    size = Size(size.width - stroke, height - stroke),
                    cornerRadius = CornerRadius(height / 2),
                    style = Stroke(stroke),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: the filter.
        Box(
            Modifier
                .fillMaxHeight()
                .heightIn(min = TOUCH_TARGET)
                .widthIn(min = TOUCH_TARGET)
                .testTag(TUNNELS_FILTER_TAG)
                .toggleable(value = filterOn, interactionSource = leftSource, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onFilter)
                .semantics { contentDescription = filterDescription },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .widthIn(min = TOUCH_TARGET)
                    .padding(vertical = margin)
                    .heightIn(min = CAPSULE_HEIGHT)
                    .clip(leftShape)
                    .background(if (filterOn) colors.accentContainer else Color.Transparent)
                    .indication(leftSource, ripple())
                    .padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Box(Modifier.size(8.dp).background(if (enabled) colors.online else colors.muted, CircleShape))
                Text(count.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        // The thin divider.
        Box(Modifier.fillMaxHeight().padding(vertical = margin).width(1.dp).background(outline))
        // Right: Stop all.
        val stopEnabled = enabled && !busy
        Box(
            Modifier
                .fillMaxHeight()
                .heightIn(min = TOUCH_TARGET)
                .widthIn(min = TOUCH_TARGET)
                .testTag(TUNNELS_STOP_ALL_TAG)
                .clickable(interactionSource = rightSource, indication = null, enabled = stopEnabled, role = Role.Button, onClick = onStopAll)
                .semantics { contentDescription = stopDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = margin)
                    .heightIn(min = CAPSULE_HEIGHT)
                    .widthIn(min = TOUCH_TARGET)
                    .clip(rightShape)
                    .indication(rightSource, ripple()),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = colors.error, strokeWidth = 2.dp)
                else Icon(PowerGlyph, contentDescription = null, tint = if (enabled) colors.error else colors.muted, modifier = Modifier.size(22.dp))
            }
        }
    }
}
