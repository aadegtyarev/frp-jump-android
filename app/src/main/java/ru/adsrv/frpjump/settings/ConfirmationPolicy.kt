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
 * A level a record attaches to. [kind], [id] and the optional [sub] are opaque strings; a target is (kind "target", id = profile, sub = connection),
 * never a joined string.
 */
@Serializable
data class ConfirmationScope(val kind: String, val id: String, val sub: String? = null)

/**
 * One persistent "For this connection" record (stage E3): the owner's long-term choice not to be asked for the [tools] of an action class on one
 * saved connection ([scope]), while the connection still has the fingerprint [fp]. Only UUIDs, a hash, class and tool wire names and a time: no name,
 * address, user, command or secret. A record only WEAKENS a question, so an older reader that does not know it asks. Read strictly, see
 * [readConnectionRecords]; [mode] is always "off" (no question).
 */
@Serializable
data class ConfirmationOverride(
    val scope: ConfirmationScope,
    @SerialName("class") val actionClass: String,
    val mode: ConfirmationMode,
    val fp: String,
    val tools: List<String>,
    /** Epoch millis; shown as a date on the connection's card. */
    val at: Long,
) {
    val target: GrantTarget? get() = scope.sub?.let { GrantTarget(scope.id, it) }
}

/**
 * "Global value + the persistent records". Persisted as `{"version":1,"global":"ask"|"off"}` (`assistant_confirmations`) and, when the owner
 * answered "For this connection", an `overrides` array of [ConfirmationOverride]. [resolve] returns the global value only: a record is never read
 * through it (it ignores class, fingerprint and tools); [ConnectionGrants] is the only reader of records.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfirmationPolicy(
    @EncodeDefault val version: Int = VERSION,
    @EncodeDefault val global: ConfirmationMode = ConfirmationMode.ASK,
    val overrides: List<ConfirmationOverride> = emptyList(),
) {
    /** The app-wide value. Records are not consulted here. */
    fun resolve(): ConfirmationMode = global

    /** The record of (class, target), if any. */
    fun recordOf(actionClass: ActionClass, target: GrantTarget): ConfirmationOverride? =
        overrides.lastOrNull { it.actionClass == actionClass.wire && it.target == target }

    /** With [record] in place of any record of the same (class, connection). */
    fun withRecord(record: ConfirmationOverride): ConfirmationPolicy =
        copy(overrides = overrides.filterNot { it.actionClass == record.actionClass && it.scope == record.scope } + record)

    /** Without the records of one connection (any class). */
    fun withoutConnection(target: GrantTarget): ConfirmationPolicy = copy(overrides = overrides.filterNot { it.target == target })

    /** Without the records of one profile. */
    fun withoutProfile(profileId: String): ConfirmationPolicy = copy(overrides = overrides.filterNot { it.scope.id == profileId })

    companion object { const val VERSION = 1 }
}

/** A saved connection as a grant target: two fields, so ids containing any character can never collide. */
data class GrantTarget(val profileId: String, val connectionId: String)

/**
 * The key of a session grant: the action class plus its target (a saved connection). Never the tool and never the command: a grant is
 * given for the class on one connection, and only the tools that are [AssistantToolId.grantable] and were in effect are covered ([SessionGrants]).
 */
data class GrantKey(val actionClass: ActionClass, val target: GrantTarget)

/** Why a call that asks in general did not ask this time; shown on its receipt in memory only, never saved. */
enum class NoQuestion { OFF, SESSION, CONNECTION }

/**
 * Pure decision: why this call needs no question, or null when it must ask. Only tools whose catalog effect changes something and that [AssistantToolId.confirms]
 * can ask. Order: the global switch is off, a session grant covers it, a persistent grant covers it; else ask. The policy and both grants are read live. Unknown
 * and read tools never ask (null with no reason: [asks] says which). Class never decides it by itself: a read class has no question, and a `confirms = false` tool
 * never asks whatever its class. A grant of another class than the tool's covers nothing (fail closed).
 */
fun noQuestion(tool: AssistantToolId?, key: GrantKey?, policy: ConfirmationPolicy, grants: SessionGrants, connection: ConnectionGrants? = null): NoQuestion? = when {
    tool == null || !askable(tool) -> null
    policy.resolve() == ConfirmationMode.OFF -> NoQuestion.OFF
    key != null && grants.covers(tool, key) -> NoQuestion.SESSION
    key != null && connection?.covers(tool, key) == true -> NoQuestion.CONNECTION
    else -> null
}

private fun askable(tool: AssistantToolId) = tool.effect.changes && tool.confirms

/** Whether [tool] is one that can ask at all (changes something and confirms); read, quiet and unknown tools never ask. */
fun asksAtAll(tool: AssistantToolId?): Boolean = tool != null && askable(tool)

/** Must this call ask the owner now? See [noQuestion]. */
fun mustAsk(tool: AssistantToolId?, key: GrantKey?, policy: ConfirmationPolicy, grants: SessionGrants, connection: ConnectionGrants? = null): Boolean =
    tool != null && askable(tool) && noQuestion(tool, key, policy, grants, connection) == null

/** Fixed texts the model receives; the owner's text is never forwarded. */
object ConfirmationTexts {
    const val DECLINED = "The owner declined this call. Nothing was executed. Do not repeat it or do the same thing another way unless the owner asks."
    const val NO_ANSWER = "No answer from the owner within 5 minutes. Nothing was executed."
    const val WAIT_LIMIT_MS = 5 * 60_000L
}
