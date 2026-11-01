package ru.adsrv.frpjump.ui.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.plugins.Exceptions
import ru.adsrv.frpjump.mqtt.plugins.PluginIds
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.settings.Chevron

/** A text button under a plugin row's hint, also offered as the row's TalkBack action. */
class PluginRowAction(val label: String, val onClick: () -> Unit)

/**
 * A plugin as one row, the same on the plugins screen and in a
 * connection's «Плагины» sheet (docs/contract.md scenario 12): the name,
 * whole and wrapped, the plugin's purpose and [notes] under it, a chevron,
 * and the switch. A tap on the switch (its own 48 dp target and ripple)
 * turns the plugin on or off; a tap on the rest opens the plugin's screen.
 * TalkBack reads the row as the name, purpose and notes with the action
 * «Настройки …», and the switch as a separate focus.
 */
@Composable
fun PluginRow(
    id: String,
    on: Boolean,
    onSwitch: (Boolean) -> Unit,
    onOpen: () -> Unit,
    /** Muted lines under the purpose: the exceptions, or where the state comes from. */
    notes: List<String> = emptyList(),
    action: PluginRowAction? = null,
    /** In a card 14 dp, as Settings' rows; in a sheet, whose content is already inset, none. */
    horizontalPadding: Dp = 14.dp,
) {
    val colors = AppTheme.colors
    val name = pluginName(id)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.plugin_settings_action, name), onClick = onOpen)
            .semantics(mergeDescendants = true) {
                if (action != null) customActions = listOf(CustomAccessibilityAction(action.label) { action.onClick(); true })
            }
            .padding(start = horizontalPadding, end = (horizontalPadding - 8.dp).coerceAtLeast(0.dp), top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(pluginHint(id), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
            notes.forEach { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted) }
            action?.let {
                TextButton(onClick = it.onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Text(it.label, fontSize = 14.sp, color = colors.accent)
                }
            }
        }
        Chevron()
        // Its own target: a tap here switches; the rest of the row opens the plugin's screen.
        Switch(checked = on, onCheckedChange = onSwitch, modifier = Modifier.semantics { contentDescription = name })
    }
}

/** What a plugin is called, as its row and its screen's title say. */
@Composable
fun pluginName(id: String): String = when (id) {
    PluginIds.WIREN_BOARD -> stringResource(R.string.plugin_wirenboard)
    PluginIds.STATUS_COMMAND -> stringResource(R.string.plugin_status_command)
    else -> id
}

/** What a plugin changes: the row's hint. */
@Composable
fun pluginHint(id: String): String = when (id) {
    PluginIds.WIREN_BOARD -> stringResource(R.string.plugin_wirenboard_hint)
    PluginIds.STATUS_COMMAND -> stringResource(R.string.plugin_status_command_hint)
    else -> ""
}

/** «Выключен для 1 подключения», «Включён для 2 подключений»: none when no connection has its own setting. */
@Composable
fun exceptionLines(exceptions: Exceptions): List<String> = buildList {
    if (exceptions.off > 0) add(pluralStringResource(R.plurals.plugin_exceptions_off, exceptions.off, exceptions.off))
    if (exceptions.on > 0) add(pluralStringResource(R.plurals.plugin_exceptions_on, exceptions.on, exceptions.on))
}
