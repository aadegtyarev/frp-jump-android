package ru.adsrv.frpjump.ui.settings

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId

/** Pins the tool-to-string mapping that used to live in the Tools and Chat screens. */
class AssistantToolTextsTest {
    @Test fun `every tool keeps its title hint and progress resources`() {
        val expected = mapOf(
            AssistantToolId.WEB_SEARCH to Triple(R.string.tool_search, R.string.tool_search_hint, R.string.tool_search_progress),
            AssistantToolId.READ_PAGE to Triple(R.string.tool_read, R.string.tool_read_hint, R.string.tool_read_progress),
            AssistantToolId.INSPECT_IMAGE to Triple(R.string.tool_image, R.string.tool_image_hint, R.string.tool_image_progress),
            AssistantToolId.PROFILE_INFORMATION to Triple(R.string.tool_profile_information, R.string.tool_profile_information_hint, R.string.tool_profile_information_progress),
            AssistantToolId.SSH_COMMAND to Triple(R.string.tool_ssh_command, R.string.tool_ssh_command_hint, R.string.tool_ssh_command_progress),
        )
        assertEquals("Every tool must be pinned", AssistantToolId.entries.toSet(), expected.keys)
        for ((id, texts) in expected) assertEquals(id.name, texts, Triple(id.title(), id.hint(), id.progress()))
    }
}
