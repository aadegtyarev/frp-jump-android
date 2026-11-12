package ru.adsrv.frpjump.assistant.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.agent.ToolSpec
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.settings.GrantTarget
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
     * What a call of a changing tool (catalog `effect.changes`) will do, for the owner's confirmation. Null for read tools; a changing
     * tool that returns null is NOT treated as read-only: [changeCheck] fails closed to an undescribed [ChangeCheck.Change].
     */
    protected open fun describeChange(args: A): ChangeRequest? = null

    /**
     * Pure, three-state. [ChangeCheck.Invalid]: the arguments do not parse (the tool then fails on its own and runs nothing).
     * [ChangeCheck.ReadOnly]: a read tool. [ChangeCheck.Change]: a changing tool; when it gives no description (or fails to), the request
     * is an undescribed one, so a changing call with valid arguments can never look read-only.
     */
    fun changeCheck(arguments: JsonObject): ChangeCheck {
        val parsed = try { parse(StrictArgs(arguments)) } catch (_: InvalidArguments) { return ChangeCheck.Invalid }
        if (!id.effect.changes) return ChangeCheck.ReadOnly
        val described = try { describeChange(parsed) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        return ChangeCheck.Change(described ?: ChangeRequest(id, null, "", described = false))
    }

    final override suspend fun execute(arguments: JsonObject): ToolOutput {
        admitSideEffect("tool-start")
        val parsed = try { parse(StrictArgs(arguments)) } catch (_: InvalidArguments) { return invalidArguments }
        return try { run(parsed) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure(error) }
    }
}

/** Not a data class: its toString must never print the command. [target] is the structured grant target; null when unknown or undescribed. */
internal class ChangeRequest(val tool: AssistantToolId, val target: GrantTarget?, val command: String, val described: Boolean = true)

internal sealed interface ChangeCheck {
    data object Invalid : ChangeCheck
    data object ReadOnly : ChangeCheck
    class Change(val request: ChangeRequest) : ChangeCheck
}
