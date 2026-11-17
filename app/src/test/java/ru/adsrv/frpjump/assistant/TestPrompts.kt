package ru.adsrv.frpjump.assistant

import java.io.File

/** The service tool's spec with every tool off (the longest one): what a request with no tool on carries beyond the prompt. */
internal val requestToolSpec get() = AssistantRequestTool(AssistantToolId.switches, emptySet()).spec

/** The shipped prompt assets, read from `src/main/assets` (JVM tests run in the module directory), without Android. */
internal object TestPrompts {
    /** Every tool switch on. */
    val allTools = ru.adsrv.frpjump.settings.AssistantTools(search = true, readPage = true, inspectImage = true, profileInformation = true, sshCommand = true,
        terminalTabs = true, terminalInput = true, tunnels = true, waits = true, sessions = true, skills = true)
    val real: AssistantPrompts by lazy { AssistantPrompts.load { File("src/main/assets/$it").inputStream() } }
    val lazy: Lazy<AssistantPrompts> = lazy { real }
    /** Dummy strings for tests that never inspect prompt text. */
    val placeholder: Lazy<AssistantPrompts> = lazy { AssistantPrompts("assistant system prompt", "assistant summary prompt") }
}
