package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.ports.DevicePort
import ru.adsrv.frpjump.ports.PRESET_PORTS
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.portsForCard
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel

data class PortRow(
    val port: DevicePort,
    val preset: Boolean,
    /** Present while desired state lists a grant for this port. */
    val tunnel: Tunnel?,
    val pending: PendingOp?,
    val error: String?,
    /** Open SSH sessions to this port, oldest first. */
    val sshSessions: List<Int> = emptyList(),
)

data class DeviceCardUiState(
    val rows: List<PortRow> = emptyList(),
    /** Tunnels up on any device of any profile: "Disconnect all" applies to all of them. */
    val tunnelCount: Int = 0,
    val syncError: String? = null,
    val mismatch: String? = null,
)

/** [device] of the current profile. */
class DeviceCardViewModel(private val app: FrpJumpApp, val device: String) : ViewModel() {
    private val runtime = checkNotNull(app.profiles.current) { "no current profile" }

    /** The profile [device] belongs to. */
    val profile: String = runtime.id

    val state: StateFlow<DeviceCardUiState> =
        combine(
            runtime.devicePorts.ports,
            runtime.tunnels.state,
            app.ssh.sessions,
            app.profiles.tunnelCount,
        ) { custom, tunnels, sessions, total ->
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
                        sshSessions = sessions.filter { it.profile == profile && it.device == device && it.port == port.port }
                            .map { it.id },
                    )
                },
                tunnelCount = total,
                syncError = tunnels.syncError,
                mismatch = tunnels.mismatch,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceCardUiState())

    fun connect(port: DevicePort) {
        runtime.tunnels.connectAsync(device, port)
    }

    fun disconnect(port: DevicePort) {
        runtime.tunnels.disconnectAsync(device, port.port)
    }

    fun disconnectAll() {
        app.profiles.disconnectAllAsync()
    }

    fun sshLogin(port: Int): SshLogin = runtime.sshLogins.get(device, port)

    /** Only whether one is kept: the password itself is unsealed by the session, off the main thread. */
    fun hasRememberedPassword(port: Int, user: String): Boolean = app.sshPasswords.has(device, port, user.trim())

    /**
     * Remembers the user name and method for this port and opens a session;
     * returns its id. An empty password means "the remembered one".
     */
    fun openSsh(port: Int, localPort: Int, user: String, method: SshAuthMethod, password: String, remember: Boolean): Int {
        val login = SshLogin(user.trim().ifEmpty { SshLogin.DEFAULT_USER }, method)
        viewModelScope.launch(Dispatchers.IO) { runtime.sshLogins.remember(device, port, login) }
        val auth = when (method) {
            SshAuthMethod.KEY -> SshAuth.Key
            SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
        }
        return app.ssh.open(profile, runtime.hostKeys, device, port, localPort, login.user, auth).id
    }

    fun addPort(port: DevicePort) = runtime.devicePorts.add(device, port)

    fun removePort(port: DevicePort) = runtime.devicePorts.remove(device, port.port)
}
