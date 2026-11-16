package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.NOT_AUTHORIZED
import ru.adsrv.frpjump.assistant.chat.receiptText
import ru.adsrv.frpjump.assistant.tools.AppTool
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.assistant.tools.StrictArgs
import ru.adsrv.frpjump.assistant.tools.objectSchema
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds

/** The most characters of the model's reason the owner is shown. */
const val OWNER_ASK_REASON_CHARS = 80

private const val SHOWN_CHARS = 64
private fun shown(text: String) = receiptText(text, SHOWN_CHARS)

/** The reply of the three session tools to a tab that is not open. */
private fun notOpen(id: Int) = ToolOutput("Tab $id is not open. Call terminal_tabs again.", ToolOutcome.FAILED)

/** The reply to a tab that is the owner's, not one of the assistant's sessions. */
private fun owners(id: Int) = ToolOutput("Tab $id is the owner's; only the owner closes it.", ToolOutcome.FAILED)

/**
 * `terminal_open`: a terminal session for the assistant on a saved SSH tunnel that is on ([AssistantSessions.open]). No confirmation: it logs in with
 * the tunnel's stored login, changes nothing on the device but a tmux session, and shows in the chat and in Sessions.
 */
internal class TerminalOpenTool(private val sessions: AssistantSessions, private val limit: Int) : AppTool<SavedTunnelIds>(
    AssistantToolId.TERMINAL_OPEN, OPEN_DESCRIPTION, objectSchema { string("profile_id", 256); string("connection_id", 256) },
) {
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id")
        SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
    }
    override val invalidArguments = ToolOutput(PROVIDE_IDS, ToolOutcome.FAILED)

    override suspend fun run(args: SavedTunnelIds): ToolOutput = when (val r = sessions.open(args, limit)) {
        is OpenResult.Opened -> ToolOutput("Session opened: tab_id ${r.id} (${shown(r.user)}@${shown(r.device)}). Type with terminal_input and read with terminal_read.")
        is OpenResult.TooMany -> ToolOutput("Already ${r.atOnce} sessions of yours, the most at once. Close one with terminal_close first.", ToolOutcome.FAILED)
        OpenResult.Unavailable -> ToolOutput(SshRefusalTexts.TARGET_UNAVAILABLE, ToolOutcome.FAILED)
        OpenResult.NeedsSetup -> ToolOutput(SshRefusalTexts.NEEDS_SETUP, ToolOutcome.FAILED)
        OpenResult.ConnectFailed -> ToolOutput(SshRefusalTexts.CONNECT_FAILED, ToolOutcome.FAILED)
        OpenResult.NoTmux -> ToolOutput("tmux is not available on the device, so a session cannot be kept. Nothing was opened.", ToolOutcome.FAILED)
    }

    override fun failure(error: Exception) = ToolOutput("Could not open a session. Check terminal_tabs.", ToolOutcome.FAILED)

    companion object {
        const val OPEN_DESCRIPTION = "Open a terminal session for yourself on a saved SSH tunnel (profile_id, connection_id from profile_information) that is on: a tmux session on the device with no tab for the owner. It survives app restarts and lost connections; terminal_tabs lists it. Returns its tab_id. Use it for long or interactive work instead of ssh_command."
    }
}

/**
 * `terminal_show`: asks the owner to act in one of the assistant's sessions ([AssistantSessions.ask]): a card in the chat that opens it as a tab, and a
 * notification when the chat is not on screen. It only asks, so no confirmation.
 */
internal class TerminalShowTool(private val sessions: AssistantSessions) : AppTool<TerminalShowTool.Input>(
    AssistantToolId.TERMINAL_SHOW, SHOW_DESCRIPTION, objectSchema { integer("tab_id", 1); string("reason", OWNER_ASK_REASON_CHARS, minLength = 1) },
) {
    class Input(val tabId: Int, val reason: String)

    override fun parse(args: StrictArgs) = args.run {
        exactKeys("tab_id", "reason")
        Input(int("tab_id", min = 1), string("reason", OWNER_ASK_REASON_CHARS))
    }
    override val invalidArguments = ToolOutput(INVALID, ToolOutcome.FAILED)

    override suspend fun run(args: Input): ToolOutput {
        val reason = receiptText(args.reason, OWNER_ASK_REASON_CHARS)
        if (!sessions.ask(args.tabId, reason)) return if (sessions.exists(args.tabId)) ToolOutput("Tab ${args.tabId} is the owner's, not one of your sessions. Nothing was asked.", ToolOutcome.FAILED) else notOpen(args.tabId)
        return ToolOutput("The owner was asked to act in tab ${args.tabId}.")
    }

    override fun failure(error: Exception) = ToolOutput("Could not ask the owner.", ToolOutcome.FAILED)

    companion object {
        const val INVALID = "Provide tab_id from terminal_tabs and a reason of up to $OWNER_ASK_REASON_CHARS characters only."
        const val SHOW_DESCRIPTION = "Ask the owner to act in one of your sessions (tab_id): a card in the chat opens it as a tab, with a notification when the chat is not on screen. Use it when the screen asks for a password or for a choice only the owner should make; then wait for the result with wait_for. `reason`: up to 80 characters, shown to the owner."
    }
}

/**
 * `terminal_close`: ends one session the assistant opened, with its tmux session on the device and anything still running in it. A changing tool:
 * it goes through the common confirmation, and [describeChange] pins the call to the session of that moment, so that what the owner was shown is what is
 * closed. An owner's tab is never closed by it.
 */
internal class TerminalCloseTool(private val sessions: AssistantSessions) : AppTool<Int>(
    AssistantToolId.TERMINAL_CLOSE, CLOSE_DESCRIPTION, objectSchema { integer("tab_id", 1) },
) {
    /** What each session was when its call was described (tab id -> pin); a call runs against the pin of its own description. */
    private class Pin(val value: Any?)
    private val pins = java.util.concurrent.ConcurrentHashMap<Int, Pin>()

    override fun parse(args: StrictArgs) = args.run {
        exactKeys("tab_id")
        int("tab_id", min = 1)
    }
    override val invalidArguments = ToolOutput(INVALID, ToolOutcome.FAILED)

    /** Nothing to ask about a tab that is unknown or the owner's: the call then says so, with no card. */
    override fun asksAbout(args: Int): Boolean = sessions.describe(args) != null

    /** Pure: reads app state, records the session's pin for [run], does no I/O. */
    override fun describeChange(args: Int): ChangeRequest {
        val card = sessions.describe(args)
        if (card == null) { pins.remove(args); return ChangeRequest(id, null, "", described = false) }
        pins[args] = Pin(sessions.pin(args))
        return ChangeRequest(id, null, "", close = card)
    }

    override suspend fun run(args: Int): ToolOutput {
        val pin = pins.remove(args)
        if (pin == null) {
            // No card was shown: the tab was unknown or the owner's when the call came, else nothing was described to the owner and nothing is closed.
            return when {
                sessions.describe(args) != null -> ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED)
                sessions.exists(args) -> owners(args)
                else -> notOpen(args)
            }
        }
        if (sessions.pin(args) != pin.value) return ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED)
        return when (sessions.close(args)) {
            CloseResult.Closed -> ToolOutput("Session $args is closed.")
            CloseResult.NotYours -> owners(args)
            CloseResult.Unknown -> notOpen(args)
            CloseResult.Unreachable -> ToolOutput("Could not reach session $args to close it. It was not closed. Try again later.", ToolOutcome.FAILED)
        }
    }

    override fun failure(error: Exception) = ToolOutput("Could not close the session. It may still run.", ToolOutcome.FAILED)

    companion object {
        const val INVALID = "Provide tab_id from terminal_tabs only."
        const val CLOSE_DESCRIPTION = "Close one session you opened (tab_id): its tmux session on the device ends, with anything still running in it. The owner may be asked before it runs."
    }
}
