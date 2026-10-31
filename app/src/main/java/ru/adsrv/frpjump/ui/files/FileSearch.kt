package ru.adsrv.frpjump.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.Found
import ru.adsrv.frpjump.files.SEARCH_CAP
import ru.adsrv.frpjump.files.SearchEnd
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.SearchGlyph

/** The search field's text matched nothing in the folder shown. */
@Composable
fun NoNameMatch(query: String) {
    Text(
        stringResource(R.string.files_search_no_match, query),
        color = AppTheme.colors.muted,
        fontSize = 14.sp,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Under the folder's matching rows: "Search subfolders", then, once
 * started, its progress or how it ended, and the hits by their path below
 * the folder. [search] is the one for the text shown, null before it starts.
 */
fun LazyListScope.subfolderSearch(
    query: String,
    search: DeepSearch?,
    online: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (Found) -> Unit,
) {
    // A name holds no `/`: say so rather than find nothing.
    if ('/' in query) {
        item(key = "/search-slash") { Hint(stringResource(R.string.files_search_slash_hint)) }
        return
    }
    if (search == null) {
        item(key = "/search-start") { StartRow(online, onStart) }
    } else {
        item(key = "/search-status") { StatusRow(search, online, onStart, onStop) }
    }
    // The device folds case as its locale does: Latin always, other letters maybe not.
    if (query.any { it.isLetter() && it.code > 127 }) {
        item(key = "/search-case") { Hint(stringResource(R.string.files_search_case_hint)) }
    }
    if (search == null) return
    items(search.found, key = { "/hit:${it.path}" }) { hit ->
        Text(
            hit.relative,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = AppTheme.colors.ink,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.files_open)) { onOpen(hit) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = AppTheme.colors.muted, modifier = Modifier.padding(start = 52.dp, end = 20.dp, bottom = 8.dp))
}

/** "Search subfolders"; offline it says why it cannot. */
@Composable
private fun StartRow(online: Boolean, onStart: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = online, role = Role.Button, onClick = onStart)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(SearchGlyph, contentDescription = null, tint = if (online) colors.accent else colors.muted, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.files_search_subfolders), fontSize = 15.sp, color = if (online) colors.accent else colors.muted)
            if (!online) Text(stringResource(R.string.files_needs_connection), fontSize = 12.sp, color = colors.muted)
        }
    }
}

/** "Searching… N found" with Stop, or how it ended, with "Search again" where that may help. */
@Composable
private fun StatusRow(search: DeepSearch, online: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = AppTheme.colors
    val n = search.found.size
    val (text, error) = when {
        search.running -> stringResource(R.string.files_searching, n) to false
        search.end == null -> stringResource(R.string.files_search_stopped, n) to false
        search.end == SearchEnd.DONE -> (if (n == 0) stringResource(R.string.files_search_none) else stringResource(R.string.files_search_found, n)) to false
        search.end == SearchEnd.CAPPED -> stringResource(R.string.files_search_capped, SEARCH_CAP) to false
        search.end == SearchEnd.TIMED_OUT -> stringResource(R.string.files_search_timed_out, n) to false
        search.end == SearchEnd.NO_FIND -> stringResource(R.string.files_search_no_find) to true
        search.end == SearchEnd.NO_COMMANDS -> stringResource(R.string.files_search_no_commands) to true
        search.end == SearchEnd.NO_ROOT -> stringResource(R.string.files_search_no_root) to true
        else -> (search.message?.let { stringResource(R.string.files_search_failed, it) } ?: stringResource(R.string.files_search_failed_plain)) to true
    }
    val again = !search.running && search.end in setOf(null, SearchEnd.TIMED_OUT, SearchEnd.FAILED)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (search.running) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text, fontSize = 14.sp, color = if (error) colors.error else colors.muted)
            if (search.skipped > 0) Text(pluralStringResource(R.plurals.files_search_skipped, search.skipped, search.skipped), fontSize = 12.sp, color = colors.muted)
        }
        when {
            search.running -> TextButton(onClick = onStop) { Text(stringResource(R.string.files_search_stop)) }
            again && online -> TextButton(onClick = onStart) { Text(stringResource(R.string.files_search_again)) }
        }
    }
}
