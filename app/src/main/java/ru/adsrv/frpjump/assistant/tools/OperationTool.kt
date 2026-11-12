package ru.adsrv.frpjump.assistant.tools

import kotlinx.serialization.json.JsonObject
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ops.OpFailure
import ru.adsrv.frpjump.ops.OpRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation

/**
 * The assistant's adapter over an [Operation]: the [AppTool] pipeline (admission, strict parsing, cancellation, fixed failure text) plus
 * the model's wording of the operation's output and of its typed refusals and failures. The confirmation (F8) takes its effect and its
 * target from the operation, so the tool cannot disagree with it. [I] is parsed from the arguments and is the operation's input.
 */
internal abstract class OperationTool<I : Any, O>(
    id: AssistantToolId,
    description: String,
    schema: JsonObject,
    protected val operation: Operation<I, O>,
) : AppTool<I>(id, description, schema) {
    init {
        // Fail closed: the confirmation reads the effect from here and from the catalog; they must be one.
        require(operation.effect == id.effect) { "${id.wire}: operation effect ${operation.effect} differs from the catalog's ${id.effect}" }
    }
    override val effect get() = operation.effect

    /** The text of a finished operation, for [input]. */
    protected abstract fun render(input: I, output: O): String
    /** How the model is told the operation finished; a command that ran and failed is FAILED although it is [OpResult.Done]. */
    protected open fun outcome(output: O): ToolOutcome = ToolOutcome.SUCCESS
    protected abstract fun modelText(refusal: OpRefusal): String
    protected abstract fun modelText(failure: OpFailure): String
    /** The failure an unexpected exception of the operation stands for. */
    protected abstract val unexpected: OpFailure
    /** What the owner is shown a changing call as; null (the default) means it cannot be described, and the call is refused. Never read for a reading tool. */
    protected open fun commandText(input: I): String? = null

    override fun describeChange(args: I): ChangeRequest? {
        val text = commandText(args)?.takeIf { it.isNotEmpty() } ?: return null
        return ChangeRequest(id, operation.target(args), text)
    }

    final override suspend fun run(args: I): ToolOutput = when (val result = operation(args)) {
        is OpResult.Done -> ToolOutput(render(args, result.value), outcome(result.value))
        is OpResult.Refused -> ToolOutput(modelText(result.reason), ToolOutcome.FAILED)
        is OpResult.Failed -> ToolOutput(modelText(result.reason), ToolOutcome.FAILED)
    }

    final override fun failure(error: Exception) = ToolOutput(modelText(unexpected), ToolOutcome.FAILED)
}
