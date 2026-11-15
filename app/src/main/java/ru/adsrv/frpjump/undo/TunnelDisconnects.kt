package ru.adsrv.frpjump.undo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.tunnel.TunnelRef

/**
 * Disconnecting tunnels with Undo (docs/contract.md, UI rules). A tunnel
 * goes dark at once: its key is in [PendingUndo.hidden], so the rows, the
 * device list, the chip, the notification and the session lists show it
 * down and its sessions gone. It runs on, with its terminal, MQTT and file
 * sessions, until the notice goes: only then [disconnectNow] tells the
 * server and stops it, and its sessions close with it. Undo shows it again
 * as it was. Several, also of several profiles, share one notice
 * ("Disconnected: 3"), each one counted. Main thread.
 */
class TunnelDisconnects(
    private val pendingUndo: PendingUndo,
    /** Whether [TunnelRef] is up now: one gone meanwhile (the notification's "Disconnect all") is not disconnected again. */
    private val isUp: (TunnelRef) -> Boolean,
    /** Disconnects [TunnelRef] for good; returns once done (failures say so on its row). */
    private val disconnectNow: suspend (TunnelRef) -> Unit,
) {
    /** Each of [tunnels] dark now, disconnected when the notice goes; one already dark is left as it is. */
    fun disconnect(tunnels: List<TunnelRef>) = tunnels.forEach { t ->
        pendingUndo.enqueue(key(t), commit = { if (isUp(t)) disconnectNow(t) }, kind = UndoKind.DISCONNECTED)
    }

    /** The tunnels being disconnected at once ([disconnectAtOnce]), until each is down: they have no Undo to show it by. */
    private val stoppingNow = MutableStateFlow<Set<TunnelRef>>(emptySet())

    /**
     * [tunnel] disconnected now, with no Undo (the assistant's Stop, answered on its card); returns once done. One already down is left.
     * It counts as [disconnecting] from the start until it is down, so that whoever asks sees the tunnel going down.
     */
    suspend fun disconnectAtOnce(tunnel: TunnelRef) {
        stoppingNow.update { it + tunnel }
        try {
            if (isUp(tunnel)) disconnectNow(tunnel)
        } finally {
            stoppingNow.update { it - tunnel }
        }
    }

    /** Whether [tunnel]'s disconnect is being done now: Undo is gone (or there was none), it is going down. */
    fun disconnecting(tunnel: TunnelRef): Boolean = pendingUndo.committing(key(tunnel)) || tunnel in stoppingNow.value

    /**
     * Suspends while [tunnel]'s disconnect is being done, so what uses it
     * next connects afresh rather than onto a tunnel going down.
     */
    suspend fun awaitDisconnected(tunnel: TunnelRef) {
        pendingUndo.awaitDone(key(tunnel))
        stoppingNow.first { tunnel !in it }
    }

    /**
     * Every tunnel went down at once by other means (the notification's
     * "Disconnect all"): the disconnects still waiting mean nothing now and
     * go, with their notice when nothing else waits on it.
     */
    fun dropWaiting() = pendingUndo.waitingKeys().filter { it.startsWith(TUNNEL_PREFIX) }.forEach { pendingUndo.withdraw(it) }

    /**
     * [tunnel] used again while its disconnect waits (Connect, Terminal,
     * Files, MQTT on its row): the disconnect alone is taken back and it
     * stays up. False when none waits.
     */
    fun revive(tunnel: TunnelRef): Boolean = pendingUndo.withdraw(key(tunnel))

    /** [up] as the owner sees them: without those dark already. */
    fun shown(up: List<TunnelRef>): List<TunnelRef> {
        val dark = PendingUndo.darkTunnels(pendingUndo.hidden.value)
        return up.filter { profilePortKey(it.profile, it.device, it.port) !in dark }
    }

    /** [up] of [profile]'s [device] as the owner sees them: what the swipe on its row disconnects. */
    fun shownOn(up: List<TunnelRef>, profile: String, device: String): List<TunnelRef> =
        shown(up).filter { it.profile == profile && it.device == device }

    private fun key(t: TunnelRef) = PendingUndo.tunnelKey(t.profile, t.device, t.port)

    private companion object {
        const val TUNNEL_PREFIX = "tunnel:"
    }
}
