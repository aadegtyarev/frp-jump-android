package ru.adsrv.frpjump.ui.mqtt

import ru.adsrv.frpjump.mqtt.MqttState

/**
 * The connection's state lives in the bar's subtitle ("Port N · state").
 * A banner under the tabs repeats it only when it adds what one line
 * cannot hold: an action (log in, retry with the reason) or why the
 * session closed. Without the subtitle (the search bar replaces it) the
 * banner says the state itself.
 */
internal fun stateNeedsBanner(state: MqttState, closedByTunnel: Boolean, subtitleShown: Boolean): Boolean = when (state) {
    MqttState.Connecting, MqttState.Reconnecting -> !subtitleShown
    MqttState.Ended -> closedByTunnel || !subtitleShown
    is MqttState.NeedsLogin, is MqttState.Failed -> true
    is MqttState.Open -> false
}

/** A state still on its way: the subtitle carries a small spinner. */
internal fun stateBusy(state: MqttState): Boolean = state == MqttState.Connecting || state == MqttState.Reconnecting
