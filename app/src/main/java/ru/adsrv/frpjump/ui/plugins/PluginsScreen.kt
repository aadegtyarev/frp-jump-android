package ru.adsrv.frpjump.ui.plugins

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AppTopBar
import ru.adsrv.frpjump.ui.connections.connectionLabel
import ru.adsrv.frpjump.ui.devices.DeviceTile
import ru.adsrv.frpjump.ui.settings.Group
import ru.adsrv.frpjump.ui.settings.RowDivider
import ru.adsrv.frpjump.ui.settings.SwitchRow

/** The plugins' view model, kept by the screen (or the navigation entry) that hosts it. */
@Composable
private fun pluginsViewModel(): PluginsViewModel {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    return viewModel { PluginsViewModel(app) }
}

/**
 * Settings › «Плагины» (docs/contract.md scenario 12): one card of plugin
 * rows, as «Компоненты и лицензии» lists its components, with the footer
 * on setting one connection apart. A row opens the plugin's screen; Back
 * closes one level at a time.
 */
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val vm = pluginsViewModel()
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    opened?.let {
        PluginScreen(it, onBack = { opened = null }, vm = vm)
        return
    }
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    PluginsFrame(stringResource(R.string.plugins_title), stringResource(R.string.plugins_subtitle), onBack) {
        val ui = state ?: return@PluginsFrame Loading()
        Group(null) {
            ui.plugins.forEachIndexed { i, plugin ->
                if (i > 0) RowDivider()
                PluginRow(
                    id = plugin.id,
                    on = plugin.on,
                    onSwitch = { vm.setOn(plugin.id, it) },
                    onOpen = { opened = plugin.id },
                    notes = exceptionLines(plugin.exceptions),
                )
            }
        }
        if (ui.unreadable) Footnote(stringResource(R.string.plugins_unreadable), AppTheme.colors.warning)
        Footnote(stringResource(R.string.plugins_footer))
    }
}

/**
 * A plugin's screen: its «Включён» switch (the same as on its row), and
 * «Свои настройки подключений» while any connection has its own setting.
 * Its own settings come in later slices (10b2–10d).
 */
@Composable
fun PluginScreen(id: String, onBack: () -> Unit, vm: PluginsViewModel = pluginsViewModel()) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    PluginsFrame(pluginName(id), null, onBack) {
        val ui = state ?: return@PluginsFrame Loading()
        val plugin = ui.plugins.firstOrNull { it.id == id } ?: return@PluginsFrame
        Group(null) {
            SwitchRow(
                tile = {},
                label = stringResource(R.string.plugin_on),
                hint = stringResource(R.string.plugin_on_hint),
                checked = plugin.on,
                onChange = { vm.setOn(id, it) },
            )
        }
        val rows = ui.exceptions[id].orEmpty()
        if (rows.isNotEmpty()) {
            Group(stringResource(R.string.plugin_exceptions_group)) {
                rows.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    ExceptionRowView(row, onFollowApp = { vm.followApp(id, row) })
                }
            }
        }
    }
}

/** A screen of the plugins: the top bar as on «Компоненты и лицензии», and groups of cards under it. */
@Composable
private fun PluginsFrame(title: String, subtitle: String?, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AppTheme.colors
    Scaffold(
        containerColor = colors.ground,
        topBar = { AppTopBar(title = title, subtitle = subtitle, onBack = onBack, wrapTitle = true) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppTheme.colors.accent)
    }
}

@Composable
private fun Footnote(text: String, color: androidx.compose.ui.graphics.Color = AppTheme.colors.muted) =
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = color, modifier = Modifier.padding(horizontal = 6.dp))

/**
 * A connection with its own setting: the device's tile, the device and the
 * connection named in full, the profile's colour when there is more than
 * one, the state, and «Как в настройках», which removes the row at once.
 */
@Composable
private fun ExceptionRowView(row: ExceptionRow, onFollowApp: () -> Unit) {
    val colors = AppTheme.colors
    val connection = connectionLabel(row.connection)
    val state = stringResource(if (row.on) R.string.plugin_on_here else R.string.plugin_off_here)
    val follow = stringResource(R.string.plugin_follow_app)
    val spoken = listOfNotNull(row.connection.device, connection, row.profileName, state).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        row.profileColor?.let {
            Box(Modifier.padding(top = 5.dp).width(4.dp).height(18.dp).background(colors.profile(it), RoundedCornerShape(2.dp)))
        }
        DeviceTile(row.icon, dot = null, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        customActions = listOf(CustomAccessibilityAction(follow) { onFollowApp(); true })
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(row.connection.device, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                Text(connection, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
                Text(state, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
            }
            TextButton(onClick = onFollowApp, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(follow, fontSize = 14.sp, color = colors.accent)
            }
        }
    }
}
