package ru.adsrv.frpjump.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
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
    /**
     * Settings › Hide in Recents: no picture of the app there. Before
     * Android 13, and on some phones after it, that also blocks screenshots.
     */
    @SerialName("hide_in_recents") val hideInRecents: Boolean = false,
    /** The web page full screen (no bars), as last left; see [ru.adsrv.frpjump.ui.rememberFullscreen]. */
    @SerialName("web_fullscreen") val webFullscreen: Boolean = false,
    /** The terminal full screen, as last left. */
    @SerialName("terminal_fullscreen") val terminalFullscreen: Boolean = false,
    /** Send IME composing text immediately by default; an explicit saved choice takes precedence. */
    @SerialName("terminal_direct_input") val terminalDirectInput: Boolean = true,
    /** Main terminal buffer rows (visible rows included), used only when a new terminal is created. */
    @SerialName("terminal_history_rows") val terminalHistoryRows: Int = DEFAULT_TERMINAL_HISTORY_ROWS,
    /** Files › ⋮ › Show hidden files: names starting with a dot. */
    @SerialName("files_show_hidden") val filesShowHidden: Boolean = false,
    /** The phone folder downloads go to: a document tree URI with a persisted grant, chosen once. */
    @SerialName("files_download_folder") val filesDownloadFolder: String? = null,
    /** Inbound MQTT accounting limits, captured by newly created explorer sessions. */
    @SerialName("mqtt_ingress") val mqttIngress: MqttIngressLimits = MqttIngressLimits(),
    @SerialName("mqtt_cache") val mqttCache: MqttCacheLimits = MqttCacheLimits(),
    /** Opt-in experimental assistant module; false also stops its active run. */
    @SerialName("assistant_enabled") val assistantEnabled: Boolean = false,
    @SerialName("assistant_search") val assistantSearch: Boolean = false,
    @SerialName("assistant_read_page") val assistantReadPage: Boolean = false,
    @SerialName("assistant_profile_information") val assistantProfileInformation: Boolean = false,
    @SerialName("assistant_ssh_command") val assistantSshCommand: Boolean = false,
    @SerialName("assistant_inspect_image") val assistantInspectImage: Boolean = false,
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

/** Independent opt-ins; there is no model capability registry or support probe. */
data class AssistantTools(val search: Boolean = false, val readPage: Boolean = false, val inspectImage: Boolean = false, val profileInformation: Boolean = false, val sshCommand: Boolean = false)
fun AppSettings.assistantTools() = AssistantTools(assistantSearch, assistantReadPage, assistantInspectImage, assistantProfileInformation, assistantSshCommand)
/** Committed preferences are captured per Send; only assistant disables revoke admitted work. */
data class AssistantAvailability(val enabled: Boolean, val disableVersion: Long = 0,
    val tools: AssistantTools = AssistantTools())

/** The app's settings in one JSON file; unreadable or invalid content falls back to the defaults. */
class SettingsStore(private val storage: TextStorage) {
    val assistantChat = ru.adsrv.frpjump.assistant.AssistantChatRuntime()
    private val _settings = MutableStateFlow(read())
    private var committedSettings = _settings.value
    private val notifications = Any()
    val settings: StateFlow<AppSettings> = _settings
    @Volatile var assistantAvailability = AssistantAvailability(_settings.value.assistantEnabled, tools = _settings.value.assistantTools())
        private set
    private val _assistantDisables = MutableStateFlow(0L)
    val assistantDisables: StateFlow<Long> = _assistantDisables

    /** Shared persisted tool operation used by Settings and the retained Chat owner. */
    fun setAssistantTool(id: String, enabled: Boolean) = update {
        when (id) {
            "web_search" -> it.copy(assistantSearch = enabled)
            "read_page" -> it.copy(assistantReadPage = enabled)
            "profile_information" -> it.copy(assistantProfileInformation = enabled)
            "ssh_command" -> it.copy(assistantSshCommand = enabled)
            "inspect_image" -> it.copy(assistantInspectImage = enabled)
            else -> error("Unknown assistant tool")
        }
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val version = synchronized(this) {
            val next = change(committedSettings)
            require(portRangeProblem(next.localPortFirst, next.localPortLast) == null) { "invalid local port range" }
            require(validTerminalHistoryRows(next.terminalHistoryRows)) { "invalid terminal history limit" }
            require(validMqttIngressLimits(next.mqttIngress)) { "invalid MQTT ingress limits" }
            require(validMqttCacheLimits(next.mqttCache)) { "invalid MQTT cache limits" }
            if (next == committedSettings) return
            storage.write(WireJson.encodeToString(next))
            synchronized(assistantChat.gate) {
                val previous = assistantAvailability
                assistantAvailability = AssistantAvailability(next.assistantEnabled,
                    previous.disableVersion + if (previous.enabled && !next.assistantEnabled) 1 else 0,
                    next.assistantTools())
                committedSettings = next
                assistantAvailability.disableVersion
            }
        }
        // Outside the monitor, including for unconfined collectors. Concurrent writers can notify
        // out of order, so the signal must never regress. The CAS transform has no side effects.
        synchronized(notifications) {
            _settings.value = synchronized(this) { committedSettings }
            _assistantDisables.update { maxOf(it, version) }
        }
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
        // Sanitize this field independently, so an unknown value/type does not reset unrelated settings.
        val rawHistory = json["terminal_history_rows"] as? JsonPrimitive
        val history = rawHistory?.takeUnless { it.isString }?.intOrNull?.takeIf(::validTerminalHistoryRows)
            ?: DEFAULT_TERMINAL_HISTORY_ROWS
        val toolFields = listOf("assistant_search", "assistant_read_page", "assistant_inspect_image", "assistant_profile_information", "assistant_ssh_command").associateWith { key ->
            JsonPrimitive((json[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false)
        }
        val normalized = JsonObject(json + toolFields + ("terminal_history_rows" to JsonPrimitive(history)) +
            ("mqtt_ingress" to normalizeMqttIngress(json["mqtt_ingress"])) +
            ("mqtt_cache" to normalizeMqttCache(json["mqtt_cache"])) +
            ("assistant_enabled" to JsonPrimitive((json["assistant_enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false)))
        val settings = WireJson.decodeFromJsonElement<AppSettings>(normalized)
        // Up to 0.2.0-beta1 the switch was the other way round, "Relay only"; written again on the next change.
        val relayOnly = (json[LEGACY_RELAY_ONLY] as? JsonPrimitive)?.booleanOrNull
        return if ("direct" !in json && relayOnly != null) settings.copy(direct = !relayOnly) else settings
    }

    /** Bad new fields never reset unrelated preferences; only an inconsistent byte pair resets together. */
    private fun normalizeMqttIngress(raw: JsonElement?): JsonElement {
        val group = raw as? JsonObject
        val defaults = MqttIngressLimits()
        fun number(key: String, range: IntRange, fallback: Int): Int =
            (group?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in range } ?: fallback
        val mode = when ((group?.get("overflow") as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "stop_session" -> MqttOverflow.STOP_SESSION
            else -> MqttOverflow.DROP_INCOMING
        }
        var limits = MqttIngressLimits(
            number("payload_bytes", MqttIngressLimits.MIN_PAYLOAD_BYTES..MqttIngressLimits.MAX_PAYLOAD_BYTES, defaults.payloadBytes),
            number("queue_bytes", MqttIngressLimits.MIN_QUEUE_BYTES..MqttIngressLimits.MAX_QUEUE_BYTES, defaults.queueBytes),
            number("queue_messages", 1..MqttIngressLimits.MAX_QUEUE_MESSAGES, defaults.queueMessages),
            mode,
        )
        if (!validMqttIngressLimits(limits)) limits = limits.copy(payloadBytes = defaults.payloadBytes, queueBytes = defaults.queueBytes)
        return WireJson.encodeToJsonElement(limits)
    }

    private fun normalizeMqttCache(raw: JsonElement?): JsonElement {
        val group = raw as? JsonObject
        val defaults = MqttCacheLimits()
        fun number(key: String, range: IntRange, fallback: Int): Int =
            (group?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in range } ?: fallback
        val mode = when ((group?.get("overflow") as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "stop_session" -> MqttOverflow.STOP_SESSION
            else -> MqttOverflow.DROP_INCOMING
        }
        return WireJson.encodeToJsonElement(MqttCacheLimits(
            number("storage_bytes", MqttCacheLimits.MIN_STORAGE_BYTES..MqttCacheLimits.MAX_STORAGE_BYTES, defaults.storageBytes),
            number("topics", 1..MqttCacheLimits.MAX_TOPICS, defaults.topics),
            number("history", 1..MqttCacheLimits.MAX_HISTORY, defaults.history), mode,
        ))
    }

    private companion object {
        const val LEGACY_RELAY_ONLY = "relay_only"
    }
}
