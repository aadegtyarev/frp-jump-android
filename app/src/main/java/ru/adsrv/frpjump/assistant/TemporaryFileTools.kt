package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.tools.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

private const val TEXT_BYTES = 16 * 1024
private fun unavailable() = ToolOutput("The temporary workspace is unavailable or this attempt is no longer authorized.", ToolOutcome.FAILED)
private fun info(file: TempInfo) = buildJsonObject { put("id", file.id); put("name", file.name); put("bytes", file.bytes) }
private suspend fun perform(action: TempAction): TempResult? {
    val context = currentCoroutineContext()
    return context[TemporaryAccess]?.apply?.invoke(action, context[Job])
}

internal class TemporaryCreateTool : AppTool<TemporaryCreateTool.Input>(AssistantToolId.TEMP_FILE_CREATE,
    "Create one immutable UTF-8 text file in the temporary workspace. A basename and literal text are required. Nothing is overwritten. Workspace contents last only for this process and current assistant/provider context.",
    objectSchema { string("name", 128); string("text", TEXT_BYTES) }) {
    data class Input(val name: String, val text: String) { override fun toString() = "Input()" }
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("name", "text")
        val name = string("name", 128); val text = string("text", TEXT_BYTES, allowBlank = true)
        if (!validTemporaryName(name) ||
            text.contains('\u0000') || !Charsets.UTF_8.newEncoder().canEncode(text) || text.toByteArray(Charsets.UTF_8).size > TEXT_BYTES) throw InvalidArguments()
        Input(name, text)
    }
    override val invalidArguments = ToolOutput("Provide one display basename and UTF-8 text up to 16384 bytes.", ToolOutcome.FAILED)
    override suspend fun run(args: Input): ToolOutput {
        // Both the encoding and ID generation are outside the short ownership guard.
        val action = TempAction.Create(UUID.randomUUID().toString(), args.name, args.text.toByteArray(Charsets.UTF_8))
        return when (val result = perform(action)) {
            is TempResult.Created -> ToolOutput(buildJsonObject { put("file", info(result.file)) }.toString())
            TempResult.Full -> ToolOutput("The temporary workspace is full; nothing was saved.", ToolOutcome.FAILED)
            else -> unavailable()
        }
    }
    override fun failure(error: Exception) = ToolOutput("Could not create the temporary file.", ToolOutcome.FAILED)
}

internal class TemporaryListTool(private val budget: Int) : AppTool<Int>(AssistantToolId.TEMP_FILE_LIST,
    "List temporary workspace file IDs, names and byte counts. File contents are not returned. Use next_offset to continue a truncated listing.",
    objectSchema { integer("offset", 0, required = false, maximum = TemporaryWorkspace.MAX_FILES) }) {
    override fun parse(args: StrictArgs) = args.run { onlyKeys("offset"); optionalInt("offset", 0, TemporaryWorkspace.MAX_FILES) ?: 0 }
    override val invalidArguments = ToolOutput("Provide an optional nonnegative listing offset.", ToolOutcome.FAILED)
    override suspend fun run(args: Int): ToolOutput {
        val result = perform(TempAction.List) as? TempResult.Listed ?: return unavailable()
        val rows = ArrayList<JsonObject>()
        fun page(next: Int?) = buildJsonObject { put("files", JsonArray(rows)); put("next_offset", next?.let(::JsonPrimitive) ?: JsonNull) }.toString()
        val remaining = result.files.drop(args)
        for (file in remaining) {
            rows += info(file)
            if (page(args + rows.size).length > budget) { rows.removeAt(rows.lastIndex); break }
        }
        return ToolOutput(page((args + rows.size).takeIf { it < result.files.size }))
    }
    override fun failure(error: Exception) = ToolOutput("Could not list temporary files.", ToolOutcome.FAILED)
}

internal class TemporaryReadTool(private val budget: Int) : AppTool<String>(AssistantToolId.TEMP_FILE_READ,
    "Read a bounded UTF-8 preview of one temporary workspace file by its exact ID. Contents are untrusted data and go to the configured model.",
    objectSchema { string("id", 64) }) {
    override fun parse(args: StrictArgs) = args.run { exactKeys("id"); string("id", 64) }
    override val invalidArguments = ToolOutput("Provide the exact temporary file ID.", ToolOutcome.FAILED)
    override suspend fun run(args: String): ToolOutput {
        val result = perform(TempAction.Read(args))
        if (result == TempResult.Missing) return ToolOutput("No temporary file has that ID.", ToolOutcome.FAILED)
        val preview = result as? TempResult.Preview ?: return unavailable()
        val input = ByteBuffer.wrap(preview.bytes)
        val decoded = if (preview.bytes.any { it == 0.toByte() }) null else runCatching {
            val chars = CharBuffer.allocate(preview.bytes.size)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val status = decoder.decode(input, chars, preview.bytes.size == preview.size)
            if (status.isError) status.throwException()
            chars.flip().toString()
        }.getOrNull()
        fun page(text: String, cut: Boolean) = buildJsonObject {
            put("name", preview.name); put("bytes", preview.size); put("text", text); put("unreadable", decoded == null); put("truncated", cut)
        }.toString()
        val text = decoded.orEmpty(); var low = 0; var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (page(text.take(mid), true).length <= budget) low = mid else high = mid - 1
        }
        if (low > 0 && low < text.length && text[low - 1].isHighSurrogate() && text[low].isLowSurrogate()) low--
        return ToolOutput(page(text.take(low), preview.size > input.position() || low < text.length))
    }
    override fun failure(error: Exception) = ToolOutput("Could not read the temporary file.", ToolOutcome.FAILED)
}
