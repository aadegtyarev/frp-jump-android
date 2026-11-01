package ru.adsrv.frpjump.tunnel

import kotlinx.coroutines.flow.StateFlow

/** What [TunnelManager] needs from frpc; [FrpcRunner] in the app, a fake in tests. */
interface FrpcControl {
    /** Per grant id; empty while frpc is not running. */
    val states: StateFlow<Map<String, VisitorState>>

    /** Local ports the running frpc listens on (or is about to). */
    val heldPorts: Set<Int>

    /** Starts, reloads or restarts frpc so that it runs [launch]. */
    fun apply(launch: FrpcLaunch, ports: Set<Int>)

    /** Stops frpc and removes the key and the config from disk. */
    fun stop()

    /** [stop], then removes everything frpc had on disk ("Forget this phone"). */
    fun wipe()

    /** Ends "Trying direct…" that went on too long ([FrpcLogParser.expire]). */
    fun expireTrying()

    /** Connections the device refused on [grant] so far ([FrpcLogParser.refusalsOf]). */
    fun refusalsOf(grant: String): Int

    /** A connection to [grant]'s port was made after [refusalsBefore] refusals ([FrpcLogParser.deviceReady]). */
    fun deviceReady(grant: String, refusalsBefore: Int)

    /** frpc's recent output, for diagnostics. */
    fun recentLog(): List<String>
}
