package ru.adsrv.frpjump.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
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
    /**
     * "Direct connection (P2P)": try a peer-to-peer tunnel and fall back to the
     * server. Off is `frp-jump-client set-p2p off`: one stcp visitor per
     * tunnel, bound directly, no attempts.
     */
    @SerialName("direct") val direct: Boolean = true,
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
        val stored = runCatching { storage.read()?.let(::decode) }.getOrNull() ?: return AppSettings()
        return if (portRangeProblem(stored.localPortFirst, stored.localPortLast) == null) {
            stored
        } else {
            stored.copy(localPortFirst = AppSettings.DEFAULT_PORTS.first, localPortLast = AppSettings.DEFAULT_PORTS.last)
        }
    }

    private fun decode(text: String): AppSettings {
        val json = WireJson.parseToJsonElement(text).jsonObject
        val settings = WireJson.decodeFromJsonElement<AppSettings>(json)
        // Up to 0.2.0-beta1 the switch was the other way round, "Relay only"; written again on the next change.
        val relayOnly = (json[LEGACY_RELAY_ONLY] as? JsonPrimitive)?.booleanOrNull
        return if ("direct" !in json && relayOnly != null) settings.copy(direct = !relayOnly) else settings
    }

    private companion object {
        const val LEGACY_RELAY_ONLY = "relay_only"
    }
}
