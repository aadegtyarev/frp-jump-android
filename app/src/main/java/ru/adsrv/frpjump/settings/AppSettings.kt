package ru.adsrv.frpjump.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.locale.AppLanguage
import ru.adsrv.frpjump.store.TextStorage

/** How often to poll with the app closed, while tunnels are up; [ms] null is off. */
@Serializable
enum class BackgroundPoll(val ms: Long?) {
    @SerialName("off") OFF(null),
    @SerialName("30s") SECONDS_30(30_000L),
    @SerialName("1m") MINUTE_1(60_000L),
    @SerialName("5m") MINUTES_5(300_000L),
    @SerialName("15m") MINUTES_15(900_000L),
}

/** Settings › Theme: as the system, or light or dark whatever the system is. */
@Serializable(with = ThemeModeSerializer::class)
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

/** By [ThemeMode.key]; a value this version does not know (written by a newer one) is [ThemeMode.SYSTEM]. */
object ThemeModeSerializer : KSerializer<ThemeMode> {
    override val descriptor = PrimitiveSerialDescriptor("ThemeMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ThemeMode) = encoder.encodeString(value.key)

    override fun deserialize(decoder: Decoder): ThemeMode {
        val key = decoder.decodeString()
        return ThemeMode.entries.firstOrNull { it.key == key } ?: ThemeMode.SYSTEM
    }
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
    /**
     * Settings › Language as last chosen in the app. Android 13+ and AppCompat
     * keep the choice themselves; this copy only stands in before AppCompat
     * has read its own on Android 10–12 ([ru.adsrv.frpjump.locale.restoreAppLanguage]).
     */
    @SerialName("language") val language: AppLanguage = AppLanguage.SYSTEM,
    /** Settings › Theme. */
    @SerialName("theme") val theme: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Settings › Unlock with fingerprint: opening the app asks for the
     * phone's biometrics or screen lock ([ru.adsrv.frpjump.lock.LockPolicy]).
     */
    @SerialName("app_lock") val appLock: Boolean = false,
) {
    val localPorts: IntRange get() = localPortFirst..localPortLast

    companion object {
        val DEFAULT_PORTS = 40000..40999
    }
}

const val MIN_LOCAL_PORT = 1024
const val MAX_LOCAL_PORT = 65535
const val MIN_LOCAL_PORTS = 10

/** Why a local port range is not usable. */
enum class PortRangeProblem { MISSING, OUTSIDE, REVERSED, TOO_SMALL }

/** Null when [first]..[last] is a usable local port range, else why not. */
fun portRangeProblem(first: Int?, last: Int?): PortRangeProblem? = when {
    first == null || last == null -> PortRangeProblem.MISSING
    first < MIN_LOCAL_PORT || last > MAX_LOCAL_PORT -> PortRangeProblem.OUTSIDE
    last < first -> PortRangeProblem.REVERSED
    last - first + 1 < MIN_LOCAL_PORTS -> PortRangeProblem.TOO_SMALL
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
