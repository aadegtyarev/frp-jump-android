package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.ports.DevicePort
import ru.adsrv.frpjump.ports.PRESET_PORTS
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.portsForCard
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel

data class PortRow(
    val port: DevicePort,
    val preset: Boolean,
    /** Present while desired state lists a grant for this port. */
    val tunnel: Tunnel?,
    val pending: PendingOp?,
    val error: String?,
)

data class DeviceCardUiState(
    val rows: List<PortRow> = emptyList(),
    /** Tunnels up on any device: "Disconnect all" applies to all of them. */
    val tunnelCount: Int = 0,
    val syncError: String? = null,
    val mismatch: String? = null,
)

class DeviceCardViewModel(private val app: FrpJumpApp, val device: String) : ViewModel() {
    val state: StateFlow<DeviceCardUiState> =
        combine(app.devicePorts.ports, app.tunnels.state) { custom, tunnels ->
            val byKey = tunnels.tunnels.associateBy { it.key }
            DeviceCardUiState(
                rows = portsForCard(custom[device].orEmpty()).map { port ->
                    val key = localPortKey(device, port.port)
                    PortRow(
                        port = port,
                        preset = PRESET_PORTS.any { it.port == port.port },
                        tunnel = byKey[key],
                        pending = tunnels.pending[key],
                        error = tunnels.errors[key],
                    )
                },
                tunnelCount = tunnels.tunnels.size,
                syncError = tunnels.syncError,
                mismatch = tunnels.mismatch,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceCardUiState())

    fun connect(port: DevicePort) {
        app.tunnels.connectAsync(device, port)
    }

    fun disconnect(port: DevicePort) {
        app.tunnels.disconnectAsync(device, port.port)
    }

    fun disconnectAll() {
        app.tunnels.disconnectAllAsync()
    }

    fun addPort(port: DevicePort) = app.devicePorts.add(device, port)

    fun removePort(port: DevicePort) = app.devicePorts.remove(device, port.port)
}
