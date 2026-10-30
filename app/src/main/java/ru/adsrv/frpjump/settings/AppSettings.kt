package ru.adsrv.frpjump.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** How often to poll with the app closed, while tunnels are up; [ms] null is off. */
@Serializable
enum class BackgroundPoll(val ms: Long?, val label: String) {
    @SerialName("off") OFF(null, "Off"),
    @SerialName("30s") SECONDS_30(30_000L, "30 s"),
    @SerialName("1m") MINUTE_1(60_000L, "1 min"),
    @SerialName("5m") MINUTES_5(300_000L, "5 min"),
    @SerialName("15m") MINUTES_15(900_000L, "15 min"),
}

@Serializable
data class AppSettings(
    @SerialName("background_poll") val backgroundPoll: BackgroundPoll = BackgroundPoll.OFF,
    /** Like `frp-jump-client set-p2p off`: one stcp visitor per tunnel, bound directly. */
    @SerialName("relay_only") val relayOnly: Boolean = false,
    @SerialName("local_port_first") val localPortFirst: Int = DEFAULT_PORTS.first,
    @SerialName("local_port_last") val localPortLast: Int = DEFAULT_PORTS.last,
) {
    val localPorts: IntRange get() = localPortFirst..localPortLast

    companion object {
        val DEFAULT_PORTS = 40000..40999
    }
}

const val MIN_LOCAL_PORT = 1024
const val MAX_LOCAL_PORT = 65535
const val MIN_LOCAL_PORTS = 10

/** Null when [first]..[last] is a usable local port range, else why not. */
fun portRangeProblem(first: Int?, last: Int?): String? = when {
    first == null || last == null -> "Enter two port numbers."
    first < MIN_LOCAL_PORT || last > MAX_LOCAL_PORT -> "Ports must be within $MIN_LOCAL_PORT-$MAX_LOCAL_PORT."
    last < first -> "The first port must not be above the last."
    last - first + 1 < MIN_LOCAL_PORTS -> "Use at least $MIN_LOCAL_PORTS ports."
    else -> null
}

/** The app's settings in one JSON file; unreadable or invalid content falls back to the defaults. */
class SettingsStore(private val storage: TextStorage) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        require(portRangeProblem(next.localPortFirst, next.localPortLast) == null) { "invalid local port range" }
        if (next == _settings.value) return
        storage.write(WireJson.encodeToString(next))
        _settings.value = next
    }

    private fun read(): AppSettings {
        val stored = runCatching { storage.read()?.let { WireJson.decodeFromString<AppSettings>(it) } }.getOrNull() ?: return AppSettings()
        return if (portRangeProblem(stored.localPortFirst, stored.localPortLast) == null) {
            stored
        } else {
            stored.copy(localPortFirst = AppSettings.DEFAULT_PORTS.first, localPortLast = AppSettings.DEFAULT_PORTS.last)
        }
    }
}
