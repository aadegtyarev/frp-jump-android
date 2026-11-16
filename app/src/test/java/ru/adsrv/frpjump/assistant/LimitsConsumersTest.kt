package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.ops.ssh.SSH_RESULT_BUDGET
import ru.adsrv.frpjump.ops.ssh.SshCapture
import ru.adsrv.frpjump.ops.ssh.captureSshStream
import ru.adsrv.frpjump.ops.ssh.fitSshStreams
import ru.adsrv.frpjump.ops.ssh.sshResultBudget
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.ssh.tmuxAttachCommand
import ru.adsrv.frpjump.ssh.tmuxReadScreenCommand

/** Each consumer of an owner's limit honours a changed value, and the model's texts say the numbers in force (the same words at the defaults). */
class LimitsConsumersTest {
    private fun noTabs() = AssistantTerminalTabs({ emptyList() }, { emptySet() }, { null }, { _, _, _ -> null })
    /** What a read with a bad argument answers: the tool's own invalid-arguments text. */
    private fun invalidRead(tool: AssistantTerminalReadTool) = runBlocking { tool.execute(buildJsonObject { put("tab_id", 1); put("lines", 99_999) }) }.text
    private val changed = AssistantLimits(inputChars = 100, keysAtOnce = 5, readLinesMax = 300, toolOutputChars = 4_200)

    @Test fun `the texts are the same words at the defaults and say the numbers in force when changed`() {
        val d = AssistantLimits()
        // Defaults: the approved wording, byte for byte.
        assertTrue(AssistantTerminalInputTool.inputDescription(d).contains("Up to 2,000 characters of text and 32 keys."))
        assertTrue(AssistantTerminalInputTool.invalidText(d).contains("Text: up to 2,000 characters, no control characters except newline and tab. Keys: up to 32 from the list."))
        assertTrue(AssistantTerminalReadTool.readDescription(d).contains("(default 100, max 1000)"))
        assertTrue(AssistantTerminalReadTool.readDescription(d).contains("limited to about 7,800 characters, newest"))
        assertEquals("Provide tab_id (an integer from terminal_tabs), optional lines 0..1000 and optional from (a cursor from an earlier read or input) only.", invalidRead(AssistantTerminalReadTool(noTabs())))
        // Changed.
        assertTrue(AssistantTerminalInputTool.inputDescription(changed).contains("Up to 100 characters of text and 5 keys."))
        assertTrue(AssistantTerminalInputTool.invalidText(changed).contains("up to 100 characters") && AssistantTerminalInputTool.invalidText(changed).contains("up to 5 from the list"))
        assertTrue(AssistantTerminalReadTool.readDescription(changed).contains("(default 100, max 300)"))
        assertTrue(AssistantTerminalReadTool.readDescription(changed).contains("about 4,000 characters"))
        assertTrue(invalidRead(AssistantTerminalReadTool(noTabs(), changed)).contains("lines 0..300"))
        assertEquals(grouped(7_800), "7,800"); assertEquals(grouped(4_000), "4,000")
    }

    @Test fun `terminal_input refuses more text and keys than the limits say, and accepts up to them`() {
        val tool = AssistantTerminalInputTool(noTabs(), changed)
        val tooLong = runBlocking { tool.execute(buildJsonObject { put("tab_id", 1); put("text", "x".repeat(101)) }) }
        assertEquals(AssistantTerminalInputTool.invalidText(changed), tooLong.text)
        assertEquals(ToolOutcome.FAILED, tooLong.outcome)
        val schema = tool.spec.parameters.toString()
        assertTrue(schema, schema.contains("\"maxLength\":100") && schema.contains("\"maxItems\":5"))
        // At its limit the text is admitted (the tab is not open: that is the next, other answer).
        val atLimit = runBlocking { tool.execute(buildJsonObject { put("tab_id", 1); put("text", "x".repeat(100)) }) }
        assertFalse(atLimit.text, atLimit.text == AssistantTerminalInputTool.invalidText(changed))
        val sixKeys = runBlocking { tool.execute(buildJsonObject { put("tab_id", 1); put("keys", kotlinx.serialization.json.JsonArray(List(6) { kotlinx.serialization.json.JsonPrimitive("enter") })) }) }
        assertEquals(AssistantTerminalInputTool.invalidText(changed), sixKeys.text)
        // The defaults still take 2,000.
        val dflt = AssistantTerminalInputTool(noTabs())
        assertFalse(runBlocking { dflt.execute(buildJsonObject { put("tab_id", 1); put("text", "x".repeat(2_000)) }) }.text == AssistantTerminalInputTool.invalidText(AssistantLimits()))
        assertEquals(AssistantTerminalInputTool.invalidText(AssistantLimits()), runBlocking { dflt.execute(buildJsonObject { put("tab_id", 1); put("text", "x".repeat(2_001)) }) }.text)
    }

    @Test fun `terminal_read takes lines up to its limit and the tmux read command allows them`() {
        val tool = AssistantTerminalReadTool(noTabs(), changed)
        val over = runBlocking { tool.execute(buildJsonObject { put("tab_id", 1); put("lines", 301) }) }
        assertEquals(invalidRead(tool), over.text)
        assertFalse(runBlocking { tool.execute(buildJsonObject { put("tab_id", 1); put("lines", 300) }) }.text == invalidRead(tool))
        // The largest limit the owner can set is a valid tmux read.
        assertTrue(tmuxReadScreenCommand("frpjump-0a1b2c-1", AssistantLimits.READ_LINES_RANGE.last).contains("-S -10000"))
        // The default `lines` is 100, clamped to a smaller maximum.
        assertEquals(100, AssistantTerminalReadTool.DEFAULT_LINES)
    }

    @Test fun `the ssh output budget follows the tool-output limit, the same 7,800 at the default`() {
        assertEquals(7_800, SSH_RESULT_BUDGET); assertEquals(sshResultBudget(AssistantLimits().toolOutputChars), SSH_RESULT_BUDGET)
        val budget = sshResultBudget(changed.toolOutputChars)
        assertEquals(4_000, budget)
        val cap = captureSshStream(java.io.ByteArrayInputStream("a".repeat(10_000).toByteArray()), budget)
        assertEquals(4_000, cap.head.length); assertEquals(10_000L, cap.total)
        val (out, err) = fitSshStreams(SshCapture("o".repeat(4_000), 9_000), SshCapture("e".repeat(3_000), 3_000), budget)
        assertTrue(out.length + err.length <= budget + 120 && err.startsWith("e".repeat(1_500)))
        val box = ru.adsrv.frpjump.profiles.FakeKeystore().box("synthetic-ssh")
        val env = ru.adsrv.frpjump.ssh.SshEnvironment(ru.adsrv.frpjump.ssh.SshKeyStore(ru.adsrv.frpjump.ssh.MemoryStorage(), box), ru.adsrv.frpjump.ssh.SshPasswords(ru.adsrv.frpjump.ssh.MemoryStorage(), box), { "synthetic" }, ru.adsrv.frpjump.ssh.TmuxNames(ru.adsrv.frpjump.ssh.MemoryStorage()))
        val sshTool = AssistantSshCommandTool(AssistantSshCommands(ru.adsrv.frpjump.ssh.SshTargets(ru.adsrv.frpjump.profiles.SyntheticProfileInformation().state, ru.adsrv.frpjump.profiles.SyntheticProfileInformation().hidden, env)), changed)
        assertTrue(sshTool.spec.description.contains("about 4,000 characters for stdout and stderr together (stderr keeps at least 1,500"))
    }

    @Test fun `the tmux attach carries the owner's terminal history in the same invocation, nothing when none is given`() {
        val plain = tmuxAttachCommand("frpjump-0a1b2c-1")
        assertFalse(plain.contains("history-limit"))
        val with = tmuxAttachCommand("frpjump-0a1b2c-1", historyRows = 5_000)
        assertTrue(with, with.contains("';' set-option -t '=frpjump-0a1b2c-1:' history-limit 5000"))
        assertEquals(1, Regex("tmux").findAll(with).count().coerceAtMost(1))
        assertTrue(with.startsWith(plain.substringBefore(" ';' set-option")))
    }

    @Test fun `the sessions attach with the owner's terminal history, and the app reads each limit from the settings at use`() {
        val session = java.io.File("src/main/java/ru/adsrv/frpjump/ssh/SshSession.kt").readText()
        assertTrue(session.contains("tmuxAttachCommand(name, openedBy, env.historyRows())"))
        val app = java.io.File("src/main/java/ru/adsrv/frpjump/FrpJumpApp.kt").readText()
        assertTrue(app.contains("historyRows = { settings.settings.value.terminalHistoryRows }"))
        assertTrue(app.contains("reconnectGiveUpMs = { settings.settings.value.assistantLimits.reconnectMinutes * 60_000L }"))
        // No second copy of a default in the consumers: the old constants are gone, or are the settings default itself.
        val runner = java.io.File("src/main/java/ru/adsrv/frpjump/assistant/chat/TurnRunner.kt").readText()
        assertFalse(runner.contains("120_000"))
        assertTrue(runner.contains("a.limits.replyLimitMs()"))
        assertEquals(AssistantLimits.DEFAULT_RECONNECT_MINUTES * 60_000L, ru.adsrv.frpjump.ssh.RECONNECT_GIVE_UP_MS)
        assertEquals(AssistantLimits.DEFAULT_TUNNEL_WAIT_S * 1000L, ru.adsrv.frpjump.ops.tunnel.TunnelUp.TUNNEL_WAIT_MS)
    }
}
