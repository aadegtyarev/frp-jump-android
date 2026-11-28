package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.JsonObject
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.agent.ToolSpec
import ru.adsrv.frpjump.agent.admitSideEffect
import ru.adsrv.frpjump.assistant.tools.InvalidArguments
import ru.adsrv.frpjump.assistant.tools.StrictArgs
import ru.adsrv.frpjump.assistant.tools.objectSchema

/**
 * The service tool `request_tool(name, why)` (Р56): the model may ask the owner to turn on one tool that is off. It runs nothing, grants
 * nothing and touches no setting or network: it only answers with a fixed text, and the owner then sees a button under the reply
 * (the chat shows it from the saved call and result). The tool stays off for the rest of the request.
 *
 * It exists only while at least one tool is off ([off] is that list, in catalog order; the `name` enum is exactly it). The off tools
 * appear to the model by name and one line each, never with their schemas. [declined] holds the wire names the owner declined in this
 * conversation. `why` is read for validity and never shown or stored anywhere else.
 */
internal class AssistantRequestTool(private val off: List<AssistantToolId>, private val declined: Set<String>) : AgentTool {
    init {
        require(off.isNotEmpty()) { "request_tool is offered only while some tool is off" }
        require(off.all { it.ownsSwitch }) { "request_tool lists switches, one entry per switch" }
    }
    private val asked = HashSet<String>()

    override val spec = ToolSpec(WIRE, description(off), objectSchema { enum("name", off.map { it.wire }); string("why", WHY_MAX) })

    override suspend fun execute(arguments: JsonObject): ToolOutput {
        admitSideEffect("tool-start")
        val name = try {
            val args = StrictArgs(arguments)
            args.exactKeys("name", "why")
            args.string("why", WHY_MAX)
            args.string("name").takeIf { value -> off.any { it.wire == value } } ?: throw InvalidArguments()
        } catch (_: InvalidArguments) { return ToolOutput(INVALID, ToolOutcome.FAILED) }
        return ToolOutput(synchronized(asked) {
            if (name in declined) declinedText(name) else if (!asked.add(name)) ALREADY else askedText(name)
        })
    }

    companion object {
        const val WIRE = "request_tool"
        const val WHY_MAX = 300
        const val ALREADY = "Already asked in this request. Do not ask again."
        const val INVALID = "Provide name (one of the listed tools) and why (one short sentence) only."
        fun askedText(tool: String) = "The owner sees a button to turn on $tool. It is off in this request."
        fun declinedText(tool: String) = "The owner declined $tool in this conversation. Do not ask again."

        /** One line per tool for the model: what it would do, never its schema. */
        private fun line(id: AssistantToolId) = when (id) {
            AssistantToolId.WEB_SEARCH -> "search the public web"
            AssistantToolId.READ_PAGE -> "read a public web page"
            AssistantToolId.INSPECT_IMAGE -> "ask the model about a public image"
            AssistantToolId.MQTT_READ -> "read private messages already observed by a connected MQTT explorer"
            AssistantToolId.PROFILE_INFORMATION -> "find the app's profiles, devices and saved tunnels"
            // One entry for both terminal tools; the wire name is terminal_tabs.
            AssistantToolId.TERMINAL_TABS, AssistantToolId.TERMINAL_READ, AssistantToolId.TERMINAL_RENAME -> "read and rename the owner's open terminal tabs"
            AssistantToolId.START_TUNNEL, AssistantToolId.STOP_TUNNEL -> "start and stop the owner's saved tunnels"
            AssistantToolId.WAIT_FOR -> "wait in the background for tab, tunnel and time events"
            AssistantToolId.TERMINAL_INPUT -> "type into the owner's open terminal tabs"
            AssistantToolId.TERMINAL_OPEN, AssistantToolId.TERMINAL_SHOW, AssistantToolId.TERMINAL_CLOSE -> "open and use its own terminal sessions"
            AssistantToolId.READ_SKILL, AssistantToolId.SKILLS_LIST, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE -> "save and delete the owner's skills"
            AssistantToolId.SSH_COMMAND -> "run one command through a saved SSH tunnel"
        }

        fun description(off: List<AssistantToolId>) =
            "Ask the owner to turn on one tool that is currently off, when the task cannot be done without it. Give the reason in one short sentence. " +
                "The tool stays off in this request: finish your answer and say what you would do with it. The owner may turn it on and continue. " +
                "Tools you can ask for: ${off.joinToString("; ") { "${it.wire} - ${line(it)}" }}."
    }
}
