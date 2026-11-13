package ru.adsrv.frpjump.ops

/*
 * The typed reasons of every operation. Kotlin seals a hierarchy per package, so all of them live here; the operations and the
 * adapters that word them live next to their domain. A payload is a fact (an exit status, a typed domain error), never
 * wording owned by this layer.
 */

import ru.adsrv.frpjump.errors.DevicePortNotOpen
import ru.adsrv.frpjump.errors.DomainError

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

sealed interface DirectRefusal : OpRefusal {
    /** The phone is on no network the choice can be tied to; nothing changed. */
    data object NoNetwork : DirectRefusal
}

sealed interface TunnelFailure : OpFailure {
    /** The tunnel did not come up in time; [error] is the last error the tunnel manager kept for it, when there is one. */
    data class DidNotComeUp(val error: DomainError?) : TunnelFailure
    /** The tunnel is up but the device does not take connections on it; [reason] says which port is not open. */
    data class DeviceNotListening(val reason: DevicePortNotOpen) : TunnelFailure
}

sealed interface MqttRefusal : OpRefusal {
    /** The session had no open connection (or none for the target); nothing was sent. */
    data object NotConnected : MqttRefusal
    /** WB write only: the control is not writable now (its meta changed or went); nothing was sent. */
    data object NotWritable : MqttRefusal
    /** WB write only: the accepted client that owned the intent is gone; nothing was sent. */
    data object NotOwner : MqttRefusal
    /** WB write only: the control's last write has no outcome yet (a double tap); nothing was sent. */
    data object Blocked : MqttRefusal
}

sealed interface MqttFailure : OpFailure {
    /** MQTT 5: the broker refused with an error reason [code] (and its [reasonString] when it sent one). The broker did not take it: `KNOWN`. */
    data class BrokerRefused(val code: Int, val reasonString: String?) : MqttFailure
    /** The connection dropped before the broker answered; the message may have arrived (`UNKNOWN`). */
    data object ConnectionLost : MqttFailure
    /** No answer within the publish timeout (`UNKNOWN`). */
    data object NoAnswer : MqttFailure
    /** Anything else; [detail] is the exception's class name (`UNKNOWN`). */
    data class Other(val detail: String?) : MqttFailure
}
