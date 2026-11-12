package ru.adsrv.frpjump.assistant.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.agent.ToolSpec
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.agent.admitSideEffect

/**
 * One assistant tool: schema, typed parsing and an action. The pipeline is written once here:
 * 1. admission (`tool-start`, throws CancellationException when the Send no longer owns the run),
 * 2. strict parsing (invalid arguments give [invalidArguments]; nothing else runs),
 * 3. [run], where CancellationException propagates and any other Exception becomes [failure].
 */
internal abstract class AppTool<A : Any>(
    val id: AssistantToolId,
    description: String,
    schema: JsonObject,
) : AgentTool {
    final override val spec = ToolSpec(id.wire, description, schema)

    /** Reads the arguments through [StrictArgs], which throws [InvalidArguments] on any violation. */
    protected abstract fun parse(args: StrictArgs): A
    protected abstract val invalidArguments: ToolOutput
    protected abstract suspend fun run(args: A): ToolOutput
    /** Fixed, sanitized text; never an exception message. */
    protected abstract fun failure(error: Exception): ToolOutput
    /**
     * What a call of a changing tool (catalog `effect.changes`) will do, for the owner's confirmation. null for read tools.
     * Every changing tool must override it (pinned by the catalog test), so none can bypass the confirmation hook.
     */
    protected open fun describeChange(args: A): ChangeRequest? = null

    /** Pure: strict parse, then the description; null for a read tool or invalid arguments (the tool then fails on its own, running nothing). */
    fun changeRequest(arguments: JsonObject): ChangeRequest? =
        try { describeChange(parse(StrictArgs(arguments))) } catch (_: InvalidArguments) { null }

    final override suspend fun execute(arguments: JsonObject): ToolOutput {
        admitSideEffect("tool-start")
        val parsed = try { parse(StrictArgs(arguments)) } catch (_: InvalidArguments) { return invalidArguments }
        return try { run(parsed) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure(error) }
    }
}

/** Not a data class: its toString must never print the command. [target] is the grant key part (ssh_command: "profileId/connectionId"). */
internal class ChangeRequest(val tool: AssistantToolId, val target: String?, val command: String)
