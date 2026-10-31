package ru.adsrv.frpjump.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R

/** The app's sections, as the bottom navigation lists them. */
enum class Tab(val label: Int, val icon: ImageVector) {
    DEVICES(R.string.tab_devices, DeviceGlyph),
    CONNECTIONS(R.string.tab_connections, LinkGlyph),
    TERMINALS(R.string.tab_terminals, TerminalGlyph),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings),
}

/**
 * The bottom navigation of the mockups: four equal items, the selected one
 * in the accent with its icon on a pill.
 */
@Composable
fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = AppTheme.colors
    Surface(color = colors.card) {
        Column {
            HorizontalDivider(color = colors.divider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    .selectableGroup(),
            ) {
                Tab.entries.forEach { tab ->
                    val on = tab == selected
                    val tint = if (on) colors.accent else colors.muted
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier
                                .background(if (on) colors.accentContainer else colors.card, CircleShape)
                                .padding(horizontal = 16.dp, vertical = 3.dp)
                                .size(20.dp),
                        )
                        FittingLabel(stringResource(tab.label), tint, bold = on)
                    }
                }
            }
        }
    }
}

/**
 * A tab's label on one line: "Подключения" under a large system font does
 * not fit a quarter of a phone, so it shrinks a step at a time until it does
 * (down to [MIN_LABEL_SP], then an ellipsis).
 */
@Composable
private fun FittingLabel(text: String, color: Color, bold: Boolean) {
    var size by remember(text) { mutableFloatStateOf(LABEL_SP) }
    Text(
        text,
        color = color,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        softWrap = false,
        overflow = if (size <= MIN_LABEL_SP) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { if (it.hasVisualOverflow && size > MIN_LABEL_SP) size -= 0.5f },
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

private const val LABEL_SP = 11f
private const val MIN_LABEL_SP = 8f
