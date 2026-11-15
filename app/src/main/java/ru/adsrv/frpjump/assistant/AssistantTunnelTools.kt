package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.NOT_AUTHORIZED
import ru.adsrv.frpjump.assistant.chat.receiptText
import ru.adsrv.frpjump.assistant.tools.AppTool
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.assistant.tools.OperationTool
import ru.adsrv.frpjump.assistant.tools.StrictArgs
import ru.adsrv.frpjump.assistant.tools.objectSchema
import ru.adsrv.frpjump.ops.OpFailure
import ru.adsrv.frpjump.ops.OpRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.TunnelToolFailure
import ru.adsrv.frpjump.ops.TunnelToolRefusal
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ops.tunnel.StopTunnelInput
import ru.adsrv.frpjump.ops.tunnel.TunnelStarted

/** The longest a tunnel's name is told to the model. */
private const val NAME_CHARS = 64

/** Owner text in a reply: cleaned of control and invisible characters, cut. */
private fun shown(name: String) = receiptText(name, NAME_CHARS)

/** The reply of both tools to ids that name no saved tunnel (also a profile that is gone or hidden). */
internal const val PROVIDE_IDS = "Provide profile_id and connection_id from profile_information."

/** `start_tunnel`: the owner's Start for one saved tunnel, through [AssistantTunnels]'s operation (the screens' own [ru.adsrv.frpjump.ops.tunnel.TunnelUp]). Runs without a question. */
internal class StartTunnelTool(tunnels: AssistantTunnels) : OperationTool<SavedTunnelIds, TunnelStarted>(
    AssistantToolId.START_TUNNEL, START_DESCRIPTION, objectSchema { string("profile_id", 256); string("connection_id", 256) }, tunnels.start,
) {
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id")
        SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
    }
    override val invalidArguments = ToolOutput(PROVIDE_IDS, ToolOutcome.FAILED)
    override fun render(input: SavedTunnelIds, output: TunnelStarted) =
        if (output.wasOn) "Tunnel ${shown(output.name)} was already on." else "Tunnel ${shown(output.name)} is on. The device takes connections."
    override val unexpected: OpFailure = TunnelToolFailure.Unexpected
    override fun modelText(refusal: OpRefusal) = PROVIDE_IDS
    override fun modelText(failure: OpFailure): String = when (val f = failure as TunnelToolFailure) {
        is TunnelToolFailure.NoConnections -> "Tunnel ${shown(f.name)} is on, but the device does not take connections yet."
        is TunnelToolFailure.DidNotComeUp -> "Tunnel ${shown(f.name)} did not come up. Nothing else was changed."
        TunnelToolFailure.Unexpected -> "Could not start the tunnel."
    }

    companion object {
        const val START_DESCRIPTION = "Start one saved tunnel from profile_information (profile_id, connection_id), as the owner's Start button does, and wait until the device takes connections through it. Changes only this app, nothing on the device."
    }
}

/**
 * `stop_tunnel`: the owner's Stop for one saved tunnel, at once and without the owner's Undo. A changing tool: it goes through the common
 * confirmation, and [describeChange] pins the call to the tunnel of that moment, so that what the owner was shown is what is stopped; a
 * tunnel that changed since (removed, edited, restarted, already stopping) is not stopped.
 */
internal class StopTunnelTool(private val tunnels: AssistantTunnels) : AppTool<SavedTunnelIds>(
    AssistantToolId.STOP_TUNNEL, STOP_DESCRIPTION, objectSchema { string("profile_id", 256); string("connection_id", 256) },
) {
    /** What each tunnel was when its call was described (ids -> pin); a call runs against the pin of its own description. */
    private class Pin(val value: Any?)
    private val pins = java.util.concurrent.ConcurrentHashMap<SavedTunnelIds, Pin>()

    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id")
        SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
    }
    override val invalidArguments = ToolOutput(PROVIDE_IDS, ToolOutcome.FAILED)

    /** Pure: reads saved state, records the tunnel's pin for [run], does no I/O. */
    override fun describeChange(args: SavedTunnelIds): ChangeRequest {
        val card = tunnels.describe(args)
        if (card == null) { pins.remove(args); return ChangeRequest(id, null, "", described = false) }
        pins[args] = Pin(tunnels.pin(args))
        return ChangeRequest(id, ru.adsrv.frpjump.settings.GrantTarget(args.profileId, args.connectionId), "", stop = card)
    }

    override suspend fun run(args: SavedTunnelIds): ToolOutput {
        // No description before (it is always made first): nothing was shown to the owner, so nothing is stopped.
        val pin = pins.remove(args) ?: return ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED)
        return when (val result = tunnels.stop(StopTunnelInput(args, pin.value))) {
            is OpResult.Done -> ToolOutput("Tunnel ${shown(result.value.name)} is off. Its tabs closed; tmux sessions on the device stay.")
            is OpResult.Refused -> when (val reason = result.reason as TunnelToolRefusal) {
                is TunnelToolRefusal.NotOn -> ToolOutput("Tunnel ${shown(reason.name)} was not on. Nothing was stopped.", ToolOutcome.FAILED)
                TunnelToolRefusal.Changed -> ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED)
                TunnelToolRefusal.Unknown -> ToolOutput(PROVIDE_IDS, ToolOutcome.FAILED)
            }
            is OpResult.Failed -> ToolOutput("Could not stop the tunnel.", ToolOutcome.FAILED)
        }
    }

    override fun failure(error: Exception) = ToolOutput("Could not stop the tunnel.", ToolOutcome.FAILED)

    companion object {
        const val STOP_DESCRIPTION = "Stop one saved tunnel (profile_id, connection_id), as the owner's Stop button does. The owner's terminal and file tabs on it close; tmux sessions on the device stay. The owner may be asked before it runs."
    }
}
