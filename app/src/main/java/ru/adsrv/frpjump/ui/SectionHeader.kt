package ru.adsrv.frpjump.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R

/** The gear at the right end of a section's header: Settings open over the section. */
@Composable
fun SettingsGear(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp).testTag(SETTINGS_GEAR_TAG)) {
        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.tab_settings), tint = AppTheme.colors.ink, modifier = Modifier.size(22.dp))
    }
}

const val SETTINGS_GEAR_TAG = "settings_gear"

/**
 * A section's title and its icon buttons ([actions]). On one row while the title, whole on one line, and the icons fit it;
 * otherwise (a large font in a narrow window) the icons take a row of their own, at the end, and the title is below them, whole:
 * wrapped between words if it must, never cut. Decided by measuring, not by the font scale.
 */
@Composable
fun SectionTitleRow(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    Layout(
        content = {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(SECTION_TITLE_TAG))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, content = actions)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val titleItem = measurables[0]
        val iconsItem = measurables[1]
        val maxWidth = constraints.maxWidth
        val icons = iconsItem.measure(Constraints(maxWidth = maxWidth))
        val naturalTitle = titleItem.maxIntrinsicWidth(Constraints.Infinity)
        if (naturalTitle + icons.width <= maxWidth) {
            val title = titleItem.measure(Constraints(maxWidth = maxWidth - icons.width))
            val height = maxOf(title.height, icons.height)
            layout(maxWidth, height) {
                title.placeRelative(0, (height - title.height) / 2)
                icons.placeRelative(maxWidth - icons.width, (height - icons.height) / 2)
            }
        } else {
            val title = titleItem.measure(Constraints(maxWidth = maxWidth))
            layout(maxWidth, icons.height + title.height) {
                icons.placeRelative(maxWidth - icons.width, 0)
                title.placeRelative(0, icons.height)
            }
        }
    }
}

const val SECTION_TITLE_TAG = "section_title"
