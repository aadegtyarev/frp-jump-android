package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.agent.TunnelReference
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.ui.AppTopBar

internal fun ConnectionRow.opensFromReference(): Boolean =
    saved && connection.kind != PortKind.TCP && pending == null &&
        (tunnel?.state?.status == TunnelStatus.DIRECT || tunnel?.state?.status == TunnelStatus.RELAYED)

@Composable
internal fun ReferencedTunnelScreen(
    vm: ConnectionsViewModel,
    target: TunnelReference,
    onBack: () -> Unit,
    onUnavailable: () -> Unit,
    onOpenWeb: (ConnectionRow) -> Unit,
    onOpenTerminal: (Int) -> Unit,
    onOpenFiles: (Int) -> Unit,
    onOpenMqtt: (Int) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.actions.sessions.collectAsStateWithLifecycle(emptyList())
    val grants by vm.actions.assistantGrants.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val ui = remember { ConnectionUi() }
    val actions = rememberRowActions(ui, vm.actions, onOpenWeb, onOpenTerminal, onOpenFiles, onOpenMqtt)
    val group = state.groups.firstOrNull { it.profile == target.profileId && it.name == target.device }
    val row = group?.rows?.firstOrNull(target::matches)
    var opened by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(lifecycle, state.loaded, row) {
        if (lifecycle != Lifecycle.State.RESUMED || !state.loaded) return@LaunchedEffect
        val current = vm.actions.referencedRow(target)
        if (current == null) {
            onUnavailable()
        } else if (!opened) {
            opened = true
            if (current.opensFromReference()) actions.main(current)
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.tab_connections), state.profileNames[target.profileId], onBack)
        if (!state.loaded) {
            CircularProgressIndicator(Modifier.padding(16.dp))
        } else if (row != null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                ConnectionCard(
                    row, state.sshMethods[row.key], row.sessionsIn(sessions), ui.menu == row.key,
                    onMenu = { show -> ui.menu = row.key.takeIf { show } },
                    actions = actions,
                    offline = group?.device?.let { !it.online || it.disabled } == true,
                    deviceName = target.device,
                    assistant = grants[ru.adsrv.frpjump.settings.GrantTarget(row.profile, row.connection.id)],
                    onForgetAssistant = { vm.actions.forgetAssistant(row) },
                )
            }
            ConnectionDialogs(
                ui, vm.actions, vm::connections, listOf(row), onOpenTerminal, onOpenFiles,
                resolveSshRow = { vm.actions.referencedRow(target) },
            )
        }
    }
}
