package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.tools.AppTool
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.settings.ConfirmationTexts
import ru.adsrv.frpjump.settings.GrantKey
import ru.adsrv.frpjump.settings.NoQuestion
import ru.adsrv.frpjump.settings.RecordResult
import ru.adsrv.frpjump.settings.asksAtAll
import ru.adsrv.frpjump.settings.noQuestion
import kotlin.coroutines.CoroutineContext

/**
 * The decision of the `authorize` hook, free of chat state so it is tested directly. Fail closed:
 * - a changing call asks unless the policy (read live NOW) is off, a session grant of its class on this connection exists that still covers this tool
 *   (same open tunnel period and connection fingerprint, the tool grantable and in effect), or a persistent "For this connection" record covers it (same
 *   fingerprint, the tool in its tools and in effect, the app not locked); the order is that, and the reason is reported through [onNoQuestion];
 * - invalid arguments and read tools return null (the tool then runs nothing for invalid arguments, and reads are never asked about);
 * - a changing call that cannot be described (no description, or a catalog tool that is not an AppTool) is REFUSED, never shown with an empty
 *   command and never run: the owner must see what runs;
 * - with no [support] at all, every changing call is refused.
 *
 * [wait] runs the question with the Send's active-time clock paused and returns the answer; a wait that runs out returns
 * [ConfirmationDecision.NO_ANSWER] (the 5-minute limit is the asker's, never an escaping timeout). [onNotSaved] reports an Always answer
 * whose "off" could not be committed: that call is allowed once and requests stay on.
 */
internal suspend fun authorizeCall(
    support: ConfirmationSupport?,
    tools: Map<String, AgentTool>,
    call: ToolCall,
    wait: suspend (ChangeRequest, ConfirmationTargetInfo?) -> ConfirmationDecision,
    onNotSaved: () -> Unit,
    io: CoroutineContext,
    /** Told why a call that asks in general did not ask; the run keeps it in memory on that call's receipt only. */
    onNoQuestion: (ToolCall, NoQuestion) -> Unit = { _, _ -> },
): ToolOutput? {
    val registered = tools[call.name] ?: return null
    val refused = ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED)
    if (registered !is AppTool<*>) {
        // A catalog tool that changes something but cannot describe itself (not an AppTool) never runs unasked: it is refused.
        return if (AssistantToolId.fromWire(call.name)?.effect?.changes == true) refused else null
    }
    val tool: AppTool<*> = registered
    val found = (tool.changeCheck(call.arguments) as? ChangeCheck.Change)?.request ?: return null
    if (support == null || !found.described) return refused
    // The card names the registered tool, not whatever identity a description claims.
    // What a For session answer on this card covers is fixed now, when the card is raised: the grant is never wider than what the owner saw.
    val request = ChangeRequest(tool.id, found.target, found.command, typing = found.typing, stop = found.stop, close = found.close, skill = found.skill,
        sessionTools = support.grants.coverableBy(tool.id), mqtt = found.mqtt, http = found.http)
    val key = request.target?.let { GrantKey(tool.id.actionClass, it) }
    // Bound to the tunnel's open period and the connection's fingerprint as they are BEFORE the question: an edit or a close during the wait makes the grant stale.
    val stamp = request.target?.let(support.grants::stamp)
    if (!asksAtAll(tool.id)) return null
    // The persistent permission reads the enrollment file (its fingerprint): never on the caller's thread, which is Main.
    val connectionStamp = request.target?.let { target -> withContext(io) { support.connection?.stamp(target) } }
    val reason = withContext(io) { noQuestion(tool.id, key, support.policy(), support.grants, support.connection) }
    if (reason != null) { onNoQuestion(call, reason); return null }
    // The card is offered a connection answer only with a connection known by name: no name, no record.
    val info = request.target?.let(support.info)
    return when (wait(request, info)) {
        ConfirmationDecision.ONCE -> null
        // A grant is not recorded (the answer acts as Allow once) for a tool that is not grantable or was switched off while the owner was being asked,
        // nor when the tunnel's period ended or the connection changed during the wait.
        ConfirmationDecision.SESSION -> { if (key != null && stamp != null) support.grants.record(key, tool.id, stamp, request.sessionTools); null }
        // The owner's choice stands even if Stop races this call (as Always); the call itself is stopped by the kernel's owner check. Recorded against the stamp taken
        // BEFORE the question and the card's tools; a failed save or a record that was refused is Allow once plus the existing note, and nothing is kept in memory instead.
        ConfirmationDecision.CONNECTION -> {
            val connection = support.connection
            if (key != null && connection != null && info != null && tool.id.grantable) {
                // Nothing recorded, whatever the reason (a card raised while locked, a change during the wait, a failed save): the call is allowed once and the owner is told.
                val result = if (connectionStamp == null) RecordResult.NOT_RECORDED
                    else withContext(NonCancellable + io) { try { connection.record(key, tool.id, connectionStamp, request.sessionTools) } catch (_: Exception) { RecordResult.SAVE_FAILED } }
                when (result) {
                    // One line speaks for the wider permission: the connection's session entry goes.
                    RecordResult.RECORDED -> support.grants.forget(key)
                    RecordResult.SAVE_FAILED, RecordResult.NOT_RECORDED -> onNotSaved()
                }
            }
            null
        }
        ConfirmationDecision.ALWAYS -> {
            // The owner's choice stands even if Stop races this call; the call itself is stopped by the kernel's owner check.
            val saved = withContext(NonCancellable + io) { try { support.persistOff() } catch (_: Exception) { false } }
            if (!saved) onNotSaved()
            null
        }
        ConfirmationDecision.DENY -> ToolOutput(ConfirmationTexts.DECLINED, ToolOutcome.DECLINED)
        ConfirmationDecision.NO_ANSWER -> ToolOutput(ConfirmationTexts.NO_ANSWER, ToolOutcome.DECLINED)
    }
}

/** The fixed text of a refusal because no confirmation could be arranged; nothing was executed. */
internal const val NOT_AUTHORIZED = "Tool was not authorized; nothing was executed."
