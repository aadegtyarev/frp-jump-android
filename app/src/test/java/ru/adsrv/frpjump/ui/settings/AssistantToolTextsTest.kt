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
            AssistantToolId.MQTT_READ to Triple(R.string.tool_mqtt_read, R.string.tool_mqtt_read_hint, R.string.tool_mqtt_read_progress),
            // The two terminal tools share one switch (its title and hint); each has its own progress line.
            AssistantToolId.START_TUNNEL to Triple(R.string.tool_tunnels, R.string.tool_tunnels_hint, R.string.tool_start_tunnel_progress),
            AssistantToolId.STOP_TUNNEL to Triple(R.string.tool_tunnels, R.string.tool_tunnels_hint, R.string.tool_stop_tunnel_progress),
            AssistantToolId.TERMINAL_TABS to Triple(R.string.tool_terminal_tabs, R.string.tool_terminal_tabs_hint, R.string.tool_terminal_tabs_progress),
            AssistantToolId.TERMINAL_READ to Triple(R.string.tool_terminal_tabs, R.string.tool_terminal_tabs_hint, R.string.tool_terminal_read_progress),
            AssistantToolId.TERMINAL_RENAME to Triple(R.string.tool_terminal_tabs, R.string.tool_terminal_tabs_hint, R.string.tool_terminal_rename_progress),
            AssistantToolId.WAIT_FOR to Triple(R.string.tool_waits, R.string.tool_waits_hint, R.string.tool_wait_for_progress),
            AssistantToolId.TERMINAL_INPUT to Triple(R.string.tool_terminal_input, R.string.tool_terminal_input_hint, R.string.tool_terminal_input_progress),
            // The three session tools share one switch (its title and hint); each has its own progress line.
            AssistantToolId.TERMINAL_OPEN to Triple(R.string.tool_sessions, R.string.tool_sessions_hint, R.string.tool_terminal_open_progress),
            AssistantToolId.TERMINAL_SHOW to Triple(R.string.tool_sessions, R.string.tool_sessions_hint, R.string.tool_terminal_show_progress),
            AssistantToolId.TERMINAL_CLOSE to Triple(R.string.tool_sessions, R.string.tool_sessions_hint, R.string.tool_terminal_close_progress),
            // The four skill tools share one switch (its title and hint); each has its own progress line.
            AssistantToolId.READ_SKILL to Triple(R.string.tool_skills, R.string.tool_skills_hint, R.string.tool_read_skill_progress),
            AssistantToolId.SKILLS_LIST to Triple(R.string.tool_skills, R.string.tool_skills_hint, R.string.tool_skills_list_progress),
            AssistantToolId.SKILL_SAVE to Triple(R.string.tool_skills, R.string.tool_skills_hint, R.string.tool_skill_save_progress),
            AssistantToolId.SKILL_DELETE to Triple(R.string.tool_skills, R.string.tool_skills_hint, R.string.tool_skill_delete_progress),
            AssistantToolId.SSH_COMMAND to Triple(R.string.tool_ssh_command, R.string.tool_ssh_command_hint, R.string.tool_ssh_command_progress),
        )
        assertEquals("Every tool must be pinned", AssistantToolId.entries.toSet(), expected.keys)
        for ((id, texts) in expected) assertEquals(id.name, texts, Triple(id.title(), id.hint(), id.progress()))
        // Receipt rows and failures name the call, so the two terminal tools differ there; every other tool uses its title.
        assertEquals(R.string.tool_start_tunnel_receipt, AssistantToolId.START_TUNNEL.receiptTitle())
        assertEquals(R.string.tool_stop_tunnel_receipt, AssistantToolId.STOP_TUNNEL.receiptTitle())
        assertEquals(R.string.tool_terminal_tabs_receipt, AssistantToolId.TERMINAL_TABS.receiptTitle())
        assertEquals(R.string.tool_terminal_read_receipt, AssistantToolId.TERMINAL_READ.receiptTitle())
        assertEquals(R.string.tool_terminal_rename_receipt, AssistantToolId.TERMINAL_RENAME.receiptTitle())
        assertEquals(R.string.tool_wait_for_receipt, AssistantToolId.WAIT_FOR.receiptTitle())
        assertEquals(R.string.tool_terminal_input_receipt, AssistantToolId.TERMINAL_INPUT.receiptTitle())
        assertEquals(R.string.tool_terminal_open_receipt, AssistantToolId.TERMINAL_OPEN.receiptTitle())
        assertEquals(R.string.tool_terminal_show_receipt, AssistantToolId.TERMINAL_SHOW.receiptTitle())
        assertEquals(R.string.tool_terminal_close_receipt, AssistantToolId.TERMINAL_CLOSE.receiptTitle())
        assertEquals(R.string.tool_read_skill_receipt, AssistantToolId.READ_SKILL.receiptTitle()); assertEquals(R.string.tool_skills_list_receipt, AssistantToolId.SKILLS_LIST.receiptTitle())
        assertEquals(R.string.tool_skill_save_receipt, AssistantToolId.SKILL_SAVE.receiptTitle()); assertEquals(R.string.tool_skill_delete_receipt, AssistantToolId.SKILL_DELETE.receiptTitle())
        for (id in AssistantToolId.entries - AssistantToolId.READ_SKILL - AssistantToolId.SKILLS_LIST - AssistantToolId.SKILL_SAVE - AssistantToolId.SKILL_DELETE - AssistantToolId.START_TUNNEL - AssistantToolId.STOP_TUNNEL - AssistantToolId.WAIT_FOR - AssistantToolId.TERMINAL_TABS - AssistantToolId.TERMINAL_READ - AssistantToolId.TERMINAL_RENAME - AssistantToolId.TERMINAL_INPUT - AssistantToolId.TERMINAL_OPEN - AssistantToolId.TERMINAL_SHOW - AssistantToolId.TERMINAL_CLOSE) assertEquals(id.name, id.title(), id.receiptTitle())
    }
}
