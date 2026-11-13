package ru.adsrv.frpjump.assistant

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The assistant's two model prompts, read once from `assets/assistant/` and never built or edited at run time:
 * [system] (`skill.md`, the base system prompt) and [summary] (`summary.md`, the working-summary prompt).
 * There is no compiled-in fallback text: an asset that is missing, malformed or empty fails the Send as a setup problem.
 *
 * Provider prefix-cache rules, which tests pin:
 * - [system] is byte-identical in every main chat-loop request of every Send; it holds no version, date, enabled-tool list,
 *   profile name or language;
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
class AssistantPrompts(val system: String, val summary: String) {
    companion object {
        const val SYSTEM_VERSION = 7
        const val SUMMARY_VERSION = 2
        const val SYSTEM_ASSET = "assistant/skill.md"
        const val SUMMARY_ASSET = "assistant/summary.md"

        /**
         * Strict UTF-8 (malformed input and a BOM are rejected); CRLF to LF; trailing whitespace trimmed once.
         * Deterministic bytes for prefix caching.
         */
        fun load(open: (String) -> InputStream) = AssistantPrompts(read(open, SYSTEM_ASSET), read(open, SUMMARY_ASSET))

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
