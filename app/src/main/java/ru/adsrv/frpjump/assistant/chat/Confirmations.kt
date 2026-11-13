package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.CompletableDeferred
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.settings.ConfirmationPolicy
import ru.adsrv.frpjump.settings.GrantStore
import ru.adsrv.frpjump.settings.GrantTarget

/** The owner's answer to a confirmation card; [NO_ANSWER] is the 5-minute wait running out. */
enum class ConfirmationDecision { ONCE, SESSION, ALWAYS, DENY, NO_ANSWER }

/** What the card shows, besides the tool and the command: names, never IDs. Null connection: "Unknown connection". */
class ConfirmationTargetInfo(val profileName: String, val connection: Connection)

/**
 * The question shown to the owner. Not a data class: [command] may hold secrets and its toString must never print it.
 * Equality is by [id] only, so a state update never compares or prints the command.
 */
class PendingConfirmation(val id: Long, val tool: AssistantToolId, val command: String, val target: GrantTarget?,
    val info: ConfirmationTargetInfo?) {
    override fun equals(other: Any?) = other is PendingConfirmation && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = "PendingConfirmation(id=$id, tool=$tool)"
}

/** The slot a pending question waits in; lives in [ChatCells] under the runtime gate. */
internal class ConfirmationSlot(val id: Long, val answer: CompletableDeferred<ConfirmationDecision>, val startedAt: Long = 0) {
    /** Real time, not the coroutine clock: the five minutes run on while the device sleeps and while no chat screen exists. */
    fun expired(now: Long) = now - startedAt >= ru.adsrv.frpjump.settings.ConfirmationTexts.WAIT_LIMIT_MS
}

/**
 * The app-side pieces a chat needs to ask: the live policy (read at each call, never captured), session grants, persisting "off"
 * for the Always answer, target names for the card and a stamp that changes when a saved target is edited or removed.
 * An [AssistantConversation] built without it asks nothing (kernel-only tests); the chat ViewModel always supplies one.
 */
class ConfirmationSupport(
    val policy: () -> ConfirmationPolicy,
    /** True when "off" was committed. Runs to completion even when Stop races the answer (the owner's choice stands). */
    val persistOff: suspend () -> Boolean,
    val info: (GrantTarget) -> ConfirmationTargetInfo? = { null },
    val stamp: (GrantTarget) -> Any? = { null },
    val grants: GrantStore = GrantStore(),
    /** Whether the tool is still switched on; a session grant is not recorded for a tool switched off while the owner was asked. */
    val toolEnabled: (AssistantToolId) -> Boolean = { true },
)
