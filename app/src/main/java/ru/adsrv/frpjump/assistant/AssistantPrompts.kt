package ru.adsrv.frpjump.assistant

import java.io.InputStream

/**
 * The assistant's two model prompts, read once from `assets/assistant/` and never built or edited at run time:
 * [system] (`skill.md`, the base system prompt) and [summary] (`summary.md`, the working-summary prompt).
 *
 * Provider prefix-cache rules, which tests pin:
 * - [system] is byte-identical in every request of every Send; it holds no version, date, tool list, profile name or language;
 * - `tools` follow the fixed catalog order with stable JSON (see `AssistantToolFactory`);
 * - everything dynamic (summary, history, the current request) comes after the system message;
 * - no provider-specific cache parameter is sent, because not every compatible API accepts one.
 * A summary request uses [summary] instead of [system] and carries no tools.
 *
 * Changing an asset requires a new version and a new pinned hash in `AssistantPromptsTest`.
 */
class AssistantPrompts(val system: String, val summary: String) {
    companion object {
        const val SYSTEM_VERSION = 4
        const val SUMMARY_VERSION = 2
        const val SYSTEM_ASSET = "assistant/skill.md"
        const val SUMMARY_ASSET = "assistant/summary.md"

        /**
         * Stand-in for callers that never inspect prompt text (most tests). Production wiring passes the loaded prompts
         * explicitly: `AssistantChatViewModel` has no default.
         */
        val PLACEHOLDER = lazy { AssistantPrompts("assistant system prompt", "assistant summary prompt") }

        /** UTF-8; CRLF to LF; trailing whitespace trimmed once. Deterministic bytes for prefix caching. */
        fun load(open: (String) -> InputStream) = AssistantPrompts(read(open, SYSTEM_ASSET), read(open, SUMMARY_ASSET))

        private fun read(open: (String) -> InputStream, path: String): String {
            val text = open(path).use { String(it.readBytes(), Charsets.UTF_8) }.replace("\r\n", "\n").trimEnd()
            require(text.isNotEmpty()) { "Empty prompt asset $path" }
            return text
        }
    }
}
