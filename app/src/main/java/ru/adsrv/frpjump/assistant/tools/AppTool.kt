package ru.adsrv.frpjump.assistant.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.agent.ToolSpec
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.WebDispatch

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
    private val dispatch: WebDispatch,
) : AgentTool {
    final override val spec = ToolSpec(id.wire, description, schema)

    /** Reads the arguments through [StrictArgs], which throws [InvalidArguments] on any violation. */
    protected abstract fun parse(args: StrictArgs): A
    protected abstract val invalidArguments: ToolOutput
    protected abstract suspend fun run(args: A): ToolOutput
    /** Fixed, sanitized text; never an exception message. */
    protected abstract fun failure(error: Exception): ToolOutput

    final override suspend fun execute(arguments: JsonObject): ToolOutput {
        dispatch("tool-start") { }
        val parsed = try { parse(StrictArgs(arguments)) } catch (_: InvalidArguments) { return invalidArguments }
        return try { run(parsed) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure(error) }
    }
}
