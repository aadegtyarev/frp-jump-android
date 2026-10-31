package ru.adsrv.frpjump.ui.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.DownloadGlyph
import ru.adsrv.frpjump.ui.SelectAllGlyph
import ru.adsrv.frpjump.ui.ShareGlyph

/**
 * The top bar while selecting: ✕ ends it, the count, Select all, and the
 * actions [available] for what is picked (more come with stage 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(
    count: Int,
    available: List<SelectionAction>,
    online: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onAction: (SelectionAction) -> Unit,
) {
    val colors = AppTheme.colors
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.ground,
            titleContentColor = colors.ink,
            navigationIconContentColor = colors.ink,
            actionIconContentColor = colors.accent,
        ),
        title = { Text(pluralStringResource(R.plurals.files_selected, count, count), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(SelectAllGlyph, contentDescription = stringResource(R.string.files_select_all), tint = colors.muted)
            }
            available.forEach { action ->
                val (icon, label) = when (action) {
                    SelectionAction.DOWNLOAD -> DownloadGlyph to R.string.files_download
                    SelectionAction.SHARE -> ShareGlyph to R.string.files_share
                }
                IconButton(onClick = { onAction(action) }, enabled = online) {
                    Icon(icon, contentDescription = stringResource(label))
                }
            }
        },
    )
}
