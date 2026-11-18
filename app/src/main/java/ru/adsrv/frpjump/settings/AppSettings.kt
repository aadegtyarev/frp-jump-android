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
import ru.adsrv.frpjump.assistant.AssistantToolId
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

/**
 * Settings › Lock after: how long the app may be out of sight before it asks
 * again; [ms] 0 is any absence. A value this version does not know is
 * [SECONDS_30] (see `normalizeAppLockAfter`).
 */
@Serializable
enum class AppLockAfter(val ms: Long) {
    @SerialName("0s") IMMEDIATELY(0L),
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
    /** Settings › Lock after: shown while [appLock] is on; kept when it goes off. */
    @SerialName("app_lock_after") val appLockAfter: AppLockAfter = AppLockAfter.SECONDS_30,
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
    /** The switch of `terminal_tabs` and `terminal_read`: the assistant sees the owner's open terminal tabs. */
    @SerialName("assistant_terminal_tabs") val assistantTerminalTabs: Boolean = false,
    /** The switch of `terminal_input`: the assistant types into an open terminal tab. Works only with [assistantTerminalTabs] on. */
    @SerialName("assistant_terminal_input") val assistantTerminalInput: Boolean = false,
    /** The switch of `start_tunnel` and `stop_tunnel`: the assistant starts and stops the owner's saved tunnels. */
    @SerialName("assistant_tunnels") val assistantTunnels: Boolean = false,
    /** The switch of `wait_for`: the assistant waits in the background for an event and answers when it happens. */
    @SerialName("assistant_waits") val assistantWaits: Boolean = false,
    /** The switch of `terminal_open`, `terminal_show` and `terminal_close`: the assistant's own terminal sessions. Works only with the tabs and typing on. */
    @SerialName("assistant_sessions") val assistantSessions: Boolean = false,
    /** The switch of `skills_list`, `skill_save` and `skill_delete`: the assistant writes the owner's skills. Reading skills (`read_skill`) needs no switch. */
    @SerialName("assistant_skills") val assistantSkills: Boolean = false,
    /** The assistant's limits (waits at once, longest wait, event replies in a row, wait after input). */
    @SerialName("assistant_limits") val assistantLimits: AssistantLimits = AssistantLimits(),
    @SerialName("assistant_inspect_image") val assistantInspectImage: Boolean = false,
    /** "Offer to turn tools on": the service tool `request_tool` may be offered to the model. On by default; off for models without tool calling. */
    @SerialName("assistant_offer_tools") val assistantOfferTools: Boolean = true,
    /** Ask before a changing assistant tool call: global value now, overrides later; absent or malformed means ask. */
    @SerialName("assistant_confirmations") val assistantConfirmations: ConfirmationPolicy = ConfirmationPolicy(),
    /** The owner's own instructions for the assistant (at most [AssistantOwnerInstructions.MAX_CHARS]); empty means none. Guidance for the model only. */
    @SerialName("assistant_owner_instructions") val assistantOwnerInstructions: String = "",
    /** The chat's text size in percent on top of the system size, one of [ChatTextSize.STEPS]; a display preference only. */
    @SerialName("assistant_chat_text_size") val assistantChatTextSize: Int = ChatTextSize.DEFAULT,
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
data class AssistantTools(val search: Boolean = false, val readPage: Boolean = false, val inspectImage: Boolean = false, val profileInformation: Boolean = false, val sshCommand: Boolean = false,
    /** One switch for `terminal_tabs` and `terminal_read`. */
    val terminalTabs: Boolean = false,
    /** The switch of `terminal_input`; it takes effect only while [terminalTabs] is on (see [get]). */
    val terminalInput: Boolean = false,
    /** One switch for `start_tunnel` and `stop_tunnel`. */
    val tunnels: Boolean = false,
    /** The switch of `wait_for`. */
    val waits: Boolean = false,
    /** One switch for `terminal_open`, `terminal_show` and `terminal_close`; takes effect only while [terminalTabs] and [terminalInput] are on (see [get]). */
    val sessions: Boolean = false,
    /** One switch for `skills_list`, `skill_save` and `skill_delete`. `read_skill` has none: it is on whenever a skill is relevant. */
    val skills: Boolean = false,
    /** "Offer to turn tools on": whether `request_tool` is offered while some tool is off. Captured at Send with the tool switches. */
    val offer: Boolean = true) {
    /** Whether the tool is in effect: its switch, and for `terminal_input` also the terminal tabs switch. Everything that asks "is this tool on" asks here. */
    operator fun get(id: AssistantToolId): Boolean = when (id) {
        AssistantToolId.TERMINAL_INPUT -> terminalInput && terminalTabs
        AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE -> sessions && terminalInput && terminalTabs
        // Reading the owner's own skills needs no switch (whether any is relevant decides whether the tool is offered).
        AssistantToolId.READ_SKILL -> true
        else -> switch(id)
    }
    /** The tool's own switch as the owner set it (the Tools screen shows this one). */
    fun switch(id: AssistantToolId): Boolean = when (id) {
        AssistantToolId.WEB_SEARCH -> search
        AssistantToolId.READ_PAGE -> readPage
        AssistantToolId.INSPECT_IMAGE -> inspectImage
        AssistantToolId.PROFILE_INFORMATION -> profileInformation
        AssistantToolId.TERMINAL_TABS, AssistantToolId.TERMINAL_READ, AssistantToolId.TERMINAL_RENAME -> terminalTabs
        AssistantToolId.TERMINAL_INPUT -> terminalInput
        AssistantToolId.START_TUNNEL, AssistantToolId.STOP_TUNNEL -> tunnels
        AssistantToolId.SSH_COMMAND -> sshCommand
        AssistantToolId.WAIT_FOR -> waits
        AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE -> sessions
        AssistantToolId.READ_SKILL, AssistantToolId.SKILLS_LIST, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE -> skills
    }
    /** Whether the model may be offered a button to turn the tool on: `terminal_input` only while the terminal tabs are on (else it asks for those first). */
    fun offerable(id: AssistantToolId): Boolean = when (id) {
        AssistantToolId.TERMINAL_INPUT -> terminalTabs
        AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE -> terminalTabs && terminalInput
        else -> true
    }
    fun with(id: AssistantToolId, enabled: Boolean): AssistantTools = when (id) {
        AssistantToolId.WEB_SEARCH -> copy(search = enabled)
        AssistantToolId.READ_PAGE -> copy(readPage = enabled)
        AssistantToolId.INSPECT_IMAGE -> copy(inspectImage = enabled)
        AssistantToolId.PROFILE_INFORMATION -> copy(profileInformation = enabled)
        // Turning the tabs off turns typing off with them, so that turning the tabs on again never brings typing back by itself.
        AssistantToolId.TERMINAL_TABS, AssistantToolId.TERMINAL_READ, AssistantToolId.TERMINAL_RENAME -> if (enabled) copy(terminalTabs = true) else copy(terminalTabs = false, terminalInput = false, sessions = false)
        // Typing off turns the assistant's own sessions off with it (they are typed into), and turning it on again never brings them back by itself.
        AssistantToolId.TERMINAL_INPUT -> if (enabled) copy(terminalInput = true) else copy(terminalInput = false, sessions = false)
        AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE -> copy(sessions = enabled)
        AssistantToolId.START_TUNNEL, AssistantToolId.STOP_TUNNEL -> copy(tunnels = enabled)
        AssistantToolId.SSH_COMMAND -> copy(sshCommand = enabled)
        AssistantToolId.WAIT_FOR -> copy(waits = enabled)
        AssistantToolId.READ_SKILL, AssistantToolId.SKILLS_LIST, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE -> copy(skills = enabled)
    }
}
fun AppSettings.assistantTools() = AssistantTools(assistantSearch, assistantReadPage, assistantInspectImage, assistantProfileInformation, assistantSshCommand, assistantTerminalTabs, assistantTerminalInput, assistantTunnels, assistantWaits, assistantSessions, assistantSkills, assistantOfferTools)
private fun AppSettings.withAssistantTools(tools: AssistantTools) = copy(assistantSearch = tools.search, assistantReadPage = tools.readPage,
    assistantInspectImage = tools.inspectImage, assistantProfileInformation = tools.profileInformation, assistantSshCommand = tools.sshCommand, assistantTerminalTabs = tools.terminalTabs, assistantTerminalInput = tools.terminalInput, assistantTunnels = tools.tunnels, assistantWaits = tools.waits, assistantSessions = tools.sessions, assistantSkills = tools.skills, assistantOfferTools = tools.offer)
/** Committed preferences are captured per Send; only assistant disables revoke admitted work. */
data class AssistantAvailability(val enabled: Boolean, val disableVersion: Long = 0,
    val tools: AssistantTools = AssistantTools(),
    /** Captured at Send admission like [tools]; text for the model only, never read for a permission or confirmation. */
    val ownerInstructions: String = "",
    /** The owner's limits, committed with the other assistant preferences; read live where a limit applies to something already running. */
    val limits: AssistantLimits = AssistantLimits(),
    /** How many times each tool went from in effect to out of effect (like [disableVersion] for the assistant): what a session grant is checked against. */
    val toolOffs: Map<AssistantToolId, Long> = emptyMap())

/** The app's settings in one JSON file; unreadable or invalid content falls back to the defaults. */
class SettingsStore(private val storage: TextStorage) {
    val assistantChat = ru.adsrv.frpjump.assistant.AssistantChatRuntime()
    private val _settings = MutableStateFlow(read())
    private var committedSettings = _settings.value
    private val notifications = Any()
    val settings: StateFlow<AppSettings> = _settings
    @Volatile var assistantAvailability = AssistantAvailability(_settings.value.assistantEnabled, tools = _settings.value.assistantTools(),
        ownerInstructions = _settings.value.assistantOwnerInstructions, limits = _settings.value.assistantLimits)
        private set
    private val _assistantDisables = MutableStateFlow(0L)
    val assistantDisables: StateFlow<Long> = _assistantDisables

    /** The committed confirmation policy, read live at each call (never captured at Send admission). */
    @Volatile var assistantConfirmations: ConfirmationPolicy = _settings.value.assistantConfirmations
        private set

    /** Shared persisted confirmation switch: only the global value. Never revokes admitted work. */
    fun setAssistantConfirmations(mode: ConfirmationMode) = update { it.copy(assistantConfirmations = it.assistantConfirmations.copy(global = mode)) }

    /** The chat's text size; any number snaps to the nearest step. Display only: it revokes and captures nothing. */
    fun setAssistantChatTextSize(percent: Int) = update { it.copy(assistantChatTextSize = ChatTextSize.snap(percent.toDouble())) }

    /** Shared persisted owner instructions; the caller passes normalized text (the store refuses anything else). */
    fun setAssistantOwnerInstructions(text: String) = update { it.copy(assistantOwnerInstructions = text) }

    /** Shared persisted tool operation used by Settings and the retained Chat owner. */
    fun setAssistantTool(id: AssistantToolId, enabled: Boolean) = update { it.withAssistantTools(it.assistantTools().with(id, enabled)) }

    /** The assistant's limits; refused outside their ranges. */
    fun setAssistantLimits(limits: AssistantLimits) = update { it.copy(assistantLimits = limits) }

    /**
     * The window group of the provider form owns the history, reply and summary shares. Until they are set here, the old values become them, once:
     * the provider's answer reserve [outputTokens] and the old trigger (the limit [AssistantLimits.summaryPercentSet] when set, else the provider
     * form's [legacyTrigger]) over its [window]; see [WindowSplit.fromLegacy]. Nothing is read again once both shares are set.
     */
    fun adoptWindowSplit(window: Int, outputTokens: Int, legacyTrigger: Int) = update { s ->
        val l = s.assistantLimits
        if (l.historyPercentSet != null && l.replyPercentSet != null) s
        else {
            val trigger = (l.summaryPercentSet ?: legacyTrigger).coerceIn(1, 100)
            val converted = WindowSplit.fromLegacy(window, outputTokens, trigger, l.summarySizePercent, l.historyPercentSet, l.replyPercentSet)
            s.copy(assistantLimits = l.withSplit(converted.split, converted.limitedFrom ?: l.replyLimitedFrom))
        }
    }

    /** The "Offer to turn tools on" switch (same autosave path as the tool switches; applies from the next Send). */
    fun setAssistantOfferTools(on: Boolean) = update { it.copy(assistantOfferTools = on) }

    fun update(change: (AppSettings) -> AppSettings) {
        val version = synchronized(this) {
            val next = change(committedSettings)
            require(portRangeProblem(next.localPortFirst, next.localPortLast) == null) { "invalid local port range" }
            require(validTerminalHistoryRows(next.terminalHistoryRows)) { "invalid terminal history limit" }
            require(validMqttIngressLimits(next.mqttIngress)) { "invalid MQTT ingress limits" }
            require(validMqttCacheLimits(next.mqttCache)) { "invalid MQTT cache limits" }
            require(validAssistantLimits(next.assistantLimits)) { "invalid assistant limits" }
            require(ChatTextSize.isStep(next.assistantChatTextSize)) { "invalid chat text size" }
            require(next.assistantOwnerInstructions == AssistantOwnerInstructions.normalize(next.assistantOwnerInstructions)) { "invalid assistant owner instructions" }
            if (next == committedSettings) return
            storage.write(WireJson.encodeToString(next))
            synchronized(assistantChat.gate) {
                val previous = assistantAvailability
                assistantAvailability = AssistantAvailability(next.assistantEnabled,
                    previous.disableVersion + if (previous.enabled && !next.assistantEnabled) 1 else 0,
                    next.assistantTools(), next.assistantOwnerInstructions, next.assistantLimits,
                    AssistantToolId.entries.associateWith { id -> (previous.toolOffs[id] ?: 0L) + if (previous.tools[id] && !next.assistantTools()[id]) 1L else 0L })
                committedSettings = next
                assistantConfirmations = next.assistantConfirmations
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
        val toolFields = AssistantToolId.switches.map { it.settingKey }.associateWith { key ->
            JsonPrimitive((json[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false)
        }
        val normalized = JsonObject(json + toolFields + ("terminal_history_rows" to JsonPrimitive(history)) +
            ("app_lock_after" to normalizeAppLockAfter(json["app_lock_after"])) +
            ("mqtt_ingress" to normalizeMqttIngress(json["mqtt_ingress"])) +
            ("mqtt_cache" to normalizeMqttCache(json["mqtt_cache"])) +
            ("assistant_limits" to normalizeAssistantLimits(json["assistant_limits"])) +
            ("assistant_confirmations" to normalizeConfirmations(json["assistant_confirmations"])) +
            ("assistant_chat_text_size" to JsonPrimitive(ChatTextSize.normalize(json["assistant_chat_text_size"]))) +
            ("assistant_owner_instructions" to JsonPrimitive(
                (json["assistant_owner_instructions"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(AssistantOwnerInstructions::normalize).orEmpty())) +
            ("assistant_enabled" to JsonPrimitive((json["assistant_enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false)))
        val settings = WireJson.decodeFromJsonElement<AppSettings>(normalized)
        // Up to 0.2.0-beta1 the switch was the other way round, "Relay only"; written again on the next change.
        val relayOnly = (json[LEGACY_RELAY_ONLY] as? JsonPrimitive)?.booleanOrNull
        return if ("direct" !in json && relayOnly != null) settings.copy(direct = !relayOnly) else settings
    }

    /** Missing, invalid or unknown is 30 s; nothing else resets. */
    private fun normalizeAppLockAfter(raw: JsonElement?): JsonElement {
        val key = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
        val known = AppLockAfter.entries.map { WireJson.encodeToJsonElement(it) }.firstOrNull { (it as JsonPrimitive).content == key }
        return known ?: WireJson.encodeToJsonElement(AppLockAfter.SECONDS_30)
    }

    /** Fail closed: only a version-1 object with global exactly "off" turns requests off; anything else is ask. Overrides are not read yet. */
    private fun normalizeConfirmations(raw: JsonElement?): JsonElement {
        val group = raw as? JsonObject
        val version = (group?.get("version") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val off = version == ConfirmationPolicy.VERSION && (group?.get("global") as? JsonPrimitive)?.takeIf { it.isString }?.content == "off"
        return WireJson.encodeToJsonElement(ConfirmationPolicy(global = if (off) ConfirmationMode.OFF else ConfirmationMode.ASK))
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

    /** Each limit is read on its own: a missing or malformed one is its default, an out-of-range one is clamped into its range, and none resets another. */
    private fun normalizeAssistantLimits(raw: JsonElement?): JsonElement {
        val group = raw as? JsonObject
        val defaults = AssistantLimits()
        // A number outside its range is clamped into it; one that is not a number is the default. Each key on its own.
        fun number(key: String, range: IntRange, fallback: Int): Int =
            (group?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.coerceIn(range) ?: fallback
        var limits = defaults
        for (spec in LIMITS) limits = spec.set(limits, number(spec.key, spec.range, spec.get(defaults)))
        // The old trigger is only the source of the conversion; the new shares stay unset (absent) until the owner or the conversion sets them.
        fun optional(key: String, range: IntRange): Int? = (group?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.coerceIn(range)
        val summary = number("summary_size_percent", WindowSplit.SUMMARY_RANGE, WindowSplit.DEFAULT_SUMMARY)
        val reply = optional("reply_percent", WindowSplit.REPLY_RANGE)
        // With the history set, the rules hold on read: the history is lowered first, then the summary gives way (the history is never raised).
        val fitted = optional("history_percent", WindowSplit.HISTORY_RANGE)?.let { WindowSplit(it, reply ?: WindowSplit.DEFAULT_REPLY, summary).fitted() }
        limits = limits.copy(summaryPercentSet = optional("summary_percent", AssistantLimits.SUMMARY_PERCENT_RANGE), summarySizePercent = fitted?.summary ?: summary,
            historyPercentSet = fitted?.history, replyPercentSet = reply,
            replyLimitedFrom = (group?.get("reply_limited_from") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 })
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
