package ru.adsrv.frpjump.settings

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.adsrv.frpjump.assistant.AssistantToolId

/** Whether a changing assistant tool call asks the owner first. [ASK] is the default and the fail-closed value. */
@Serializable
enum class ConfirmationMode {
    @SerialName("ask") ASK,
    @SerialName("off") OFF,
}

/**
 * A level an override may attach to (future: device, tunnel/target). [kind], [id] and the optional [sub] are opaque strings, so a new
 * level needs no new fields; a target is (kind "target", id = profile, sub = connection), never a joined string.
 */
@Serializable
data class ConfirmationScope(val kind: String, val id: String, val sub: String? = null)

@Serializable
data class ConfirmationOverride(val scope: ConfirmationScope, val mode: ConfirmationMode)

/**
 * "Global value + future overrides". Persisted as `{"version":1,"global":"ask"|"off"}` (`assistant_confirmations`); overrides, when
 * added, extend the same object with an optional `overrides` array. Only [global] is read or written for now: [overrides] is always
 * empty in stored data, but [resolve] already takes the scopes of a call, so adding a level changes no call site.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfirmationPolicy(
    @EncodeDefault val version: Int = VERSION,
    @EncodeDefault val global: ConfirmationMode = ConfirmationMode.ASK,
    val overrides: List<ConfirmationOverride> = emptyList(),
) {
    /** The most specific override among [scopes] (given most specific first), else [global]. */
    fun resolve(scopes: List<ConfirmationScope> = emptyList()): ConfirmationMode =
        scopes.firstNotNullOfOrNull { scope -> overrides.firstOrNull { it.scope == scope }?.mode } ?: global

    companion object { const val VERSION = 1 }
}

/** A saved connection as a grant target: two fields, so ids containing any character can never collide. */
data class GrantTarget(val profileId: String, val connectionId: String)

/** The key of a session grant: the tool plus its target (ssh_command: profile and connection). Never the command. */
data class GrantKey(val tool: AssistantToolId, val target: GrantTarget?)

/** Session grants, in memory only, owned by one chat screen. Thread-safe; never persisted, never in history. */
class GrantStore {
    private val keys = HashSet<GrantKey>()
    fun has(key: GrantKey): Boolean = synchronized(keys) { key in keys }
    fun grant(key: GrantKey) { synchronized(keys) { keys += key } }
    fun clear() = synchronized(keys) { keys.clear() }
    fun clear(tool: AssistantToolId) = synchronized(keys) { keys.removeAll { it.tool == tool } }
    /** A grant must not follow an edited or removed target: drop the grants of one connection, or of a whole profile when [connectionId] is null. */
    fun clearTarget(profileId: String, connectionId: String? = null) = synchronized(keys) {
        keys.removeAll { key -> key.target?.let { it.profileId == profileId && (connectionId == null || it.connectionId == connectionId) } == true }
    }
    fun snapshot(): Set<GrantKey> = synchronized(keys) { keys.toSet() }
}

/**
 * Pure decision: must this call ask the owner? Only tools whose catalog effect changes something can ask; the policy (read live at
 * each call) and a session grant can waive the question. Unknown and read tools never ask.
 */
fun mustAsk(tool: AssistantToolId?, key: GrantKey?, policy: ConfirmationPolicy, grants: GrantStore, scopes: List<ConfirmationScope> = emptyList()): Boolean =
    tool != null && tool.effect.changes && policy.resolve(scopes) == ConfirmationMode.ASK && (key == null || !grants.has(key))

/** Fixed texts the model receives; the owner's text is never forwarded. */
object ConfirmationTexts {
    const val DECLINED = "The owner declined this call. Nothing was executed. Do not repeat it or do the same thing another way unless the owner asks."
    const val NO_ANSWER = "No answer from the owner within 5 minutes. Nothing was executed."
    const val WAIT_LIMIT_MS = 5 * 60_000L
}
