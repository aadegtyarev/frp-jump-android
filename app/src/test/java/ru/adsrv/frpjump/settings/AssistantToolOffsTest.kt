package ru.adsrv.frpjump.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ssh.MemoryStorage

/** The committed availability counts each switch-off of a tool (in effect to out of effect), like the assistant's own off count. */
class AssistantToolOffsTest {
    private val ssh = AssistantToolId.SSH_COMMAND
    private fun offs(s: SettingsStore, id: AssistantToolId) = s.assistantAvailability.toolOffs[id] ?: 0L

    @Test fun `a switch off counts once and a switch on does not undo it`() {
        val s = SettingsStore(MemoryStorage())
        s.setAssistantTool(ssh, true); assertEquals(0, offs(s, ssh))
        s.setAssistantTool(ssh, false); assertEquals(1, offs(s, ssh))
        s.setAssistantTool(ssh, true); assertEquals(1, offs(s, ssh))
        s.setAssistantTool(ssh, false); assertEquals(2, offs(s, ssh))
        s.setAssistantTool(ssh, false); assertEquals("no change, no count", 2, offs(s, ssh))
    }

    @Test fun `typing leaves effect with the terminal tabs and counts, other tools are untouched`() {
        val s = SettingsStore(MemoryStorage())
        s.setAssistantTool(AssistantToolId.TERMINAL_TABS, true); s.setAssistantTool(AssistantToolId.TERMINAL_INPUT, true)
        s.setAssistantTool(AssistantToolId.TERMINAL_TABS, false)
        assertEquals(1, offs(s, AssistantToolId.TERMINAL_INPUT)); assertEquals(1, offs(s, AssistantToolId.TERMINAL_TABS)); assertEquals(0, offs(s, ssh))
    }
}
