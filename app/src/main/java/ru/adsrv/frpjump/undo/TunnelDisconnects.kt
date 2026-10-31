package ru.adsrv.frpjump.undo

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
    /** Disconnects [TunnelRef] for good; returns once done (failures say so on its row). */
    private val disconnectNow: suspend (TunnelRef) -> Unit,
) {
    /** Each of [tunnels] dark now, disconnected when the notice goes; one already dark is left as it is. */
    fun disconnect(tunnels: List<TunnelRef>) = tunnels.forEach { t ->
        pendingUndo.enqueue(key(t), commit = { disconnectNow(t) }, kind = UndoKind.DISCONNECTED)
    }

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

    private fun key(t: TunnelRef) = PendingUndo.tunnelKey(t.profile, t.device, t.port)
}
