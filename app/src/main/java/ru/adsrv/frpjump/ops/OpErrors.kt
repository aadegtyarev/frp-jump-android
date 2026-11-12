package ru.adsrv.frpjump.ops

/*
 * The typed reasons of every operation. Kotlin seals a hierarchy per package, so all of them live here; the operations and the
 * adapters that word them live next to their domain. A payload is a fact (an exit status, a message the tunnel manager made), never
 * wording owned by this layer.
 */

/** Nothing was started. */
sealed interface OpRefusal

/** Something was started and did not finish; see [RemoteOutcome]. */
sealed interface OpFailure

/** What a remote command reported before the connection was lost, when it reported anything. */
data class SshExit(val status: Int?, val signal: String?)

sealed interface SshRefusal : OpRefusal {
    /** No saved, ready, visible SSH target. No tunnel was started. */
    data object TargetUnavailable : SshRefusal
    /** The stored login or the host trust needs the ordinary SSH UI. */
    data object NeedsSetup : SshRefusal
    /** The target went away before the command was sent. */
    data object TargetLostBeforeExec : SshRefusal
    /** The connection could not be made before the command was sent. */
    data object ConnectFailed : SshRefusal
}

sealed interface SshFailure : OpFailure {
    /** The target went away after the command was sent; [exit] is what the channel had already reported. */
    data class TargetLost(val exit: SshExit?) : SshFailure
    /** The command failed after it was sent; [exit] is what the channel had already reported. */
    data class CommandFailed(val exit: SshExit?) : SshFailure
    /** An unexpected exception of the operation; the remote outcome is unknown. */
    data object Unexpected : SshFailure
}

sealed interface ProfileQueryFailure : OpFailure {
    /** Refreshing or reading the profile information failed. No successful refresh is implied. */
    data object Unavailable : ProfileQueryFailure
}

sealed interface TunnelRefusal : OpRefusal {
    /** The profile was forgotten. */
    data object ProfileGone : TunnelRefusal
}

sealed interface TunnelFailure : OpFailure {
    /** The tunnel did not come up in time; [detail] is the last error the tunnel manager kept for it, when there is one. */
    data class DidNotComeUp(val detail: String?) : TunnelFailure
    /** The tunnel is up but the device does not take connections on it; [detail] is the tunnel manager's explanation. */
    data class DeviceNotListening(val detail: String) : TunnelFailure
}
