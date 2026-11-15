package ru.adsrv.frpjump.assistant

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import ru.adsrv.frpjump.settings.AssistantTools

/**
 * The assistant's model prompts, read once from `assets/assistant/` and never edited at run time: the core of the system prompt (`skill.md`:
 * the role, the rules every request needs, a [TOOL_NOTES] line where the tools' notes go), one note per tool switch (`tools/<switch>.md`,
 * the setting key without `assistant_`) and the working-summary prompt (`summary.md`).
 * There is no compiled-in fallback text: an asset that is missing, malformed or empty fails the Send as a setup problem.
 *
 * [system] is the ONE function that composes the system prompt, and every reader uses it: the core with the notes of the switches that are on in
 * the request, in the catalog's switch order (`AssistantToolId.switches`), at the [TOOL_NOTES] line. A tool's guidance lives in its own note,
 * so a request with the tool off does not carry it.
 *
 * Provider prefix-cache rules, which tests pin:
 * - the system text of a request is a function of the tool switches alone and is byte-identical in every main chat-loop request of a Send; it
 *   holds no version, date, profile name or language;
 * - `tools` follow the fixed catalog order with stable JSON (see `AssistantToolFactory`);
 * - the owner's own instructions, when set, are appended to the one system message after [system] (blank line, fixed header, text;
 *   `AgentRequest.systemContent`), so [system] stays its exact byte prefix and there is never a second system message; without
 *   them the message is exactly [system]; summary requests never carry the owner text;
 * - everything dynamic (summary, history, the current request) comes after the system message;
 * - no provider-specific cache parameter is sent (`OpenAiCacheNeutralityTest`), because not every compatible API accepts one.
 * A summary request uses [summary] instead of [system] and carries no tools.
 *
 * Changing an asset requires a new version and a new pinned hash in `AssistantPromptsTest`.
 */
class AssistantPrompts(val core: String, val summary: String, private val notes: Map<AssistantToolId, String> = emptyMap()) {
    /** The system prompt of a request in which the tool switches are [tools]. */
    fun system(tools: AssistantTools): String {
        val on = AssistantToolId.switches.filter { tools[it] }.mapNotNull { notes[it] }
        val lines = core.split('\n')
        if (TOOL_NOTES !in lines) return (listOf(core) + on).joinToString("\n\n")
        return lines.flatMap { if (it == TOOL_NOTES) on else listOf(it) }.joinToString("\n")
    }

    companion object {
        const val SYSTEM_VERSION = 17
        const val SUMMARY_VERSION = 2
        const val SYSTEM_ASSET = "assistant/skill.md"
        const val SUMMARY_ASSET = "assistant/summary.md"
        /** The line of the core where the notes of the tools that are on go (the line itself is never sent). */
        const val TOOL_NOTES = "<tool-notes>"

        /** The asset of a switch's note. */
        fun noteAsset(id: AssistantToolId) = "assistant/tools/${id.settingKey.removePrefix("assistant_")}.md"

        /**
         * Strict UTF-8 (malformed input and a BOM are rejected); CRLF to LF; trailing whitespace trimmed once.
         * Deterministic bytes for prefix caching.
         */
        fun load(open: (String) -> InputStream) = AssistantPrompts(read(open, SYSTEM_ASSET), read(open, SUMMARY_ASSET),
            AssistantToolId.switches.associateWith { read(open, noteAsset(it)) })

        private fun read(open: (String) -> InputStream, path: String): String {
            val bytes = open(path).use { it.readBytes() }
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (failure: CharacterCodingException) {
                throw IllegalArgumentException("Prompt asset $path is not valid UTF-8", failure)
            }
            require(!decoded.startsWith('\uFEFF')) { "Prompt asset $path starts with a byte order mark" }
            val text = decoded.replace("\r\n", "\n").trimEnd()
            require(text.isNotEmpty()) { "Empty prompt asset $path" }
            return text
        }
    }
}
