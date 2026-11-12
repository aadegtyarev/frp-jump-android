package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.tools.AppTool
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.settings.ConfirmationTexts
import ru.adsrv.frpjump.settings.GrantKey
import ru.adsrv.frpjump.settings.mustAsk
import kotlin.coroutines.CoroutineContext

/**
 * The decision of the `authorize` hook, free of chat state so it is tested directly. Fail closed: a changing call asks unless the policy
 * (read live NOW) is off or a session grant for this exact target and its current definition exists. Invalid arguments and read tools
 * return null (the tool then runs nothing for invalid arguments, and reads are never asked about). A changing call with valid arguments
 * but no description is still asked (unknown target, no session grant possible); it never runs unasked.
 *
 * [wait] runs the question with the Send's active-time clock paused and returns the answer; a wait that runs out returns
 * [ConfirmationDecision.NO_ANSWER] (the 5-minute limit is the asker's, never an escaping timeout). [onNotSaved] reports an Always answer
 * whose "off" could not be committed: that call is allowed once and requests stay on.
 */
internal suspend fun authorizeCall(
    support: ConfirmationSupport,
    tools: Map<String, AppTool<*>>,
    call: ToolCall,
    wait: suspend (ChangeRequest, ConfirmationTargetInfo?) -> ConfirmationDecision,
    onNotSaved: () -> Unit,
    io: CoroutineContext,
): ToolOutput? {
    val tool = tools[call.name] ?: return null
    val request = (tool.changeCheck(call.arguments) as? ChangeCheck.Change)?.request ?: return null
    val key = request.target?.let { GrantKey(request.tool, it) }
    val stamp = request.target?.let(support.stamp)
    if (!mustAsk(request.tool, key, support.policy(), support.grants, stamp = stamp)) return null
    return when (wait(request, request.target?.let(support.info))) {
        ConfirmationDecision.ONCE -> null
        ConfirmationDecision.SESSION -> { if (key != null) support.grants.grant(key, stamp); null }
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
