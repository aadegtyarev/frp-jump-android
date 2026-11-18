package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import ru.adsrv.frpjump.ui.LinkGlyph
import ru.adsrv.frpjump.ui.LocalAppLocked
import ru.adsrv.frpjump.ui.PowerGlyph
import ru.adsrv.frpjump.ui.SearchGlyph
import ru.adsrv.frpjump.ui.SettingsGear

const val TUNNELS_HEADING_TAG = "tunnels_heading"
const val TUNNELS_SEARCH_TAG = "tunnels_search"
const val TUNNELS_SEARCH_FIELD_TAG = "tunnels_search_field"
const val TUNNELS_SEARCH_BACK_TAG = "tunnels_search_back"
const val TUNNELS_SEARCH_CLEAR_TAG = "tunnels_search_clear"
const val TUNNELS_FILTER_TAG = "tunnels_filter"
const val TUNNELS_STOP_ALL_TAG = "tunnels_stop_all"

/**
 * The room the collapsed search keeps in the header's one row: 130 dp at 100% font, growing with the size body text really has (system
 * font scaling is not linear, so the measured size of 16 sp is used, not the scale), so a large font wraps the header.
 */
private const val SEARCH_ROOM_DP = 130

/**
 * The Tunnels tab's header, with no visible title (the bottom bar names the tab; TalkBack gets an invisible heading).
 *
 * Collapsed: one row, [search pill][filter][⏻ Stop all][gear]. When they do not fit (a large font in a narrow window; decided by measuring)
 * two rows: search and gear, then the filter and ⏻. Expanded ([searching]): the field takes the whole header, with ← (close) and ✕ (clear).
 *
 * [filterOn] is what the filter does now (the choice, and something is on); [count] is the tunnels that are on. The filter is disabled and
 * Stop all is disabled at 0, in their places, so the header never moves.
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
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.tab_connections)
    Box(modifier.fillMaxWidth()) {
        // The heading screen readers land on: invisible and tiny.
        Box(Modifier.size(1.dp).testTag(TUNNELS_HEADING_TAG).semantics(mergeDescendants = true) { heading(); contentDescription = title })
        if (searching) {
            SearchField(query, onQuery, onCloseSearch, focusOnOpen, onFocused, scopeOn)
        } else {
            HeaderRows(
                search = { SearchPill(onOpenSearch) },
                filter = { FilterToggle(filterOn, count, onFilter) },
                stop = { StopAll(count, stopBusy, onStopAll) },
                gear = { SettingsGear(onSettings) },
            )
        }
    }
}

/** Lays the four items out by measuring: one row when the search keeps its room, otherwise two (see [TunnelsHeader]). */
@Composable
private fun HeaderRows(search: @Composable () -> Unit, filter: @Composable () -> Unit, stop: @Composable () -> Unit, gear: @Composable () -> Unit) {
    Layout(content = { search(); filter(); stop(); gear() }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val loose = Constraints(maxWidth = maxWidth)
        val filterItem = measurables[1].measure(loose)
        val stopItem = measurables[2].measure(loose)
        val gearItem = measurables[3].measure(loose)
        val naturalSearch = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val searchWants = maxOf(naturalSearch, (SEARCH_ROOM_DP.dp.toPx() * (16.sp.toPx() / 16.dp.toPx())).roundToInt())
        val oneRow = searchWants + filterItem.width + stopItem.width + gearItem.width + 3 * gap <= maxWidth
        if (oneRow) {
            val searchWidth = maxWidth - filterItem.width - stopItem.width - gearItem.width - 3 * gap
            val searchItem = measurables[0].measure(Constraints.fixedWidth(searchWidth))
            val height = maxOf(searchItem.height, filterItem.height, stopItem.height, gearItem.height)
            layout(maxWidth, height) {
                var x = 0
                for (item in listOf(searchItem, filterItem, stopItem, gearItem)) {
                    item.placeRelative(x, (height - item.height) / 2)
                    x += item.width + gap
                }
            }
        } else {
            val searchWidth = (maxWidth - gearItem.width - gap).coerceAtLeast(0)
            val searchItem = measurables[0].measure(Constraints.fixedWidth(searchWidth))
            val first = maxOf(searchItem.height, gearItem.height)
            val second = maxOf(filterItem.height, stopItem.height)
            layout(maxWidth, first + second) {
                searchItem.placeRelative(0, (first - searchItem.height) / 2)
                gearItem.placeRelative(maxWidth - gearItem.width, (first - gearItem.height) / 2)
                filterItem.placeRelative(0, first + (second - filterItem.height) / 2)
                stopItem.placeRelative(filterItem.width + gap, first + (second - stopItem.height) / 2)
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

/**
 * The filter "only tunnels that are on": a 32 dp toggle pill (touch target 44 dp) with the link glyph and the count. Disabled at 0. TalkBack:
 * "Only tunnels that are on, N", as a switch with its state.
 */
@Composable
private fun FilterToggle(on: Boolean, count: Int, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val enabled = count > 0
    val description = stringResource(R.string.connections_only_on, count)
    val ink = when {
        !enabled -> colors.muted
        on -> colors.onAccentContainer
        else -> colors.ink
    }
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .testTag(TUNNELS_FILTER_TAG)
            .clip(CircleShape)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .background(if (on && enabled) colors.accentContainer else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                .border(1.dp, if (on && enabled) colors.accent else colors.outline, CircleShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(LinkGlyph, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Text(count.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

/** ⏻ Stop all: always in its place, disabled when nothing is on; a spinner while the servers are told. */
@Composable
private fun StopAll(count: Int, busy: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val enabled = count > 0 && !busy
    RoundButton(
        description = stringResource(R.string.disconnect_all_count, count),
        onClick = onClick,
        enabled = enabled,
        border = if (enabled || busy) colors.error else colors.outline,
        modifier = Modifier.testTag(TUNNELS_STOP_ALL_TAG),
    ) { size ->
        if (busy) CircularProgressIndicator(Modifier.size(size * 0.56f), color = colors.error, strokeWidth = 2.dp)
        else Icon(PowerGlyph, contentDescription = null, tint = if (enabled) colors.error else colors.muted, modifier = Modifier.size(size * 0.56f))
    }
}
