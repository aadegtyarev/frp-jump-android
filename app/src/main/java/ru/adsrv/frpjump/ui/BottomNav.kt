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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)
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
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier
                                .background(if (on) colors.accentContainer else colors.card, CircleShape)
                                .padding(horizontal = 18.dp, vertical = 4.dp)
                                .size(22.dp),
                        )
                        Text(
                            stringResource(tab.label),
                            color = tint,
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
