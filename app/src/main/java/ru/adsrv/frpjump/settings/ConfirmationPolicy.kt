package ru.adsrv.frpjump.settings

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ops.ActionClass

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

/**
 * The key of a session grant: the action class plus its target (a saved connection). Never the tool and never the command: a grant is
 * given for the class on one connection, and only the tools that are [AssistantToolId.grantable] and were in effect are covered ([SessionGrants]).
 */
data class GrantKey(val actionClass: ActionClass, val target: GrantTarget)

/**
 * Pure decision: must this call ask the owner? Only tools whose catalog effect changes something and that [AssistantToolId.confirms] can ask; the policy (read live at
 * each call) and a session grant can waive the question. Unknown and read tools never ask. Class never decides it by itself: a read class has no
 * question, and a `confirms = false` tool never asks whatever its class. A grant of another class than the tool's covers nothing (fail closed).
 */
fun mustAsk(tool: AssistantToolId?, key: GrantKey?, policy: ConfirmationPolicy, grants: SessionGrants, scopes: List<ConfirmationScope> = emptyList()): Boolean =
    tool != null && tool.effect.changes && tool.confirms && policy.resolve(scopes) == ConfirmationMode.ASK && (key == null || !grants.covers(tool, key))

/** Fixed texts the model receives; the owner's text is never forwarded. */
object ConfirmationTexts {
    const val DECLINED = "The owner declined this call. Nothing was executed. Do not repeat it or do the same thing another way unless the owner asks."
    const val NO_ANSWER = "No answer from the owner within 5 minutes. Nothing was executed."
    const val WAIT_LIMIT_MS = 5 * 60_000L
}
