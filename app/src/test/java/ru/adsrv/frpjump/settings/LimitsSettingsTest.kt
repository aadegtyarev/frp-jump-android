package ru.adsrv.frpjump.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentLimits
import ru.adsrv.frpjump.assistant.chat.withOwnerLimits
import ru.adsrv.frpjump.ssh.MemoryStorage

/** The one table of the owner's limits: every key's default, range and step, normalized each on its own, and how a Send reads them. */
class LimitsSettingsTest {
    private val approved = mapOf(
        "skills_max" to Triple(50, 1..200, 1), "skill_chars" to Triple(4_000, 500..20_000, 500), "skills_list_chars" to Triple(2_000, 500..8_000, 500),
        "tool_timeout_s" to Triple(30, 10..300, 5), "model_timeout_s" to Triple(60, 10..600, 10), "reply_s" to Triple(120, 30..1800, 30),
        "steps_per_reply" to Triple(12, 1..50, 1), "calls_per_step" to Triple(4, 1..10, 1), "tool_output_chars" to Triple(8_000, 2_000..32_000, 1_000),
        "reply_chars" to Triple(32_000, 4_000..128_000, 4_000), "waits_at_once" to Triple(3, 1..10, 1), "wait_max_minutes" to Triple(30, 1..120, 1),
        "event_replies" to Triple(5, 1..20, 1), "sessions_at_once" to Triple(3, 1..10, 1), "input_chars" to Triple(2_000, 100..10_000, 100),
        "keys_at_once" to Triple(32, 1..100, 1), "read_lines_max" to Triple(1_000, 100..10_000, 100), "tunnel_wait_s" to Triple(45, 5..120, 5),
        "reconnect_minutes" to Triple(10, 1..60, 1),
        "summary_percent" to Triple(80, 10..90, 5), "summary_size_percent" to Triple(15, 5..40, 5), "fresh_tool_percent" to Triple(20, 5..50, 5),
    )

    @Test fun `every limit has the approved default, range and step`() {
        assertEquals(approved.keys, LIMITS.map { it.key }.toSet())
        val defaults = AssistantLimits()
        for (spec in LIMITS) {
            val (default, range, step) = approved.getValue(spec.key)
            assertEquals(spec.key, default, spec.get(defaults)); assertEquals(spec.key, range, spec.range); assertEquals(spec.key, step, spec.step)
            assertTrue(spec.key, default in range)
        }
        assertTrue(validAssistantLimits(defaults))
    }

    @Test fun `each key is clamped into its range on its own, out of range is refused when set and clamped when read, and a malformed one resets only itself`() {
        for (spec in LIMITS) {
            val d = AssistantLimits()
            assertFalse(spec.key, validAssistantLimits(spec.set(d, spec.range.first - 1)))
            assertFalse(spec.key, validAssistantLimits(spec.set(d, spec.range.last + 1)))
            assertTrue(spec.key, validAssistantLimits(spec.set(d, spec.range.first)) && validAssistantLimits(spec.set(d, spec.range.last)))
            val control = LIMITS.first { it.key != spec.key }
            // Out of range in a file: clamped into the range (not reset to the default); another key is untouched.
            // The summary size alone is held to what the trigger leaves (95 - 80); the pair is tested on its own below.
            val top = if (spec.key == "summary_size_percent") AssistantLimits.SUMMARY_PAIR_MAX - AssistantLimits.DEFAULT_SUMMARY_PERCENT else spec.range.last
            val over = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":${spec.range.last + 1},"${control.key}":${control.range.last}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, top, spec.get(over)); assertEquals(spec.key, control.range.last, control.get(over))
            val under = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":${spec.range.first - 1}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, spec.range.first, spec.get(under))
            // Malformed (not a number): the default, and only that key.
            val text = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":"many","${control.key}":${control.range.last}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, spec.get(AssistantLimits()), spec.get(text)); assertEquals(spec.key, control.range.last, control.get(text))
            val good = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":${spec.range.last}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, top, spec.get(good))
        }
        assertEquals(AssistantLimits(), SettingsStore(MemoryStorage("""{"assistant_enabled":true}""")).settings.value.assistantLimits)
    }

    @Test fun `a saved limit is read back after a restart`() {
        val file = MemoryStorage()
        SettingsStore(file).setAssistantLimits(AssistantLimits(toolTimeoutSeconds = 90, tunnelWaitSeconds = 15, reconnectMinutes = 3, readLinesMax = 5_000))
        val back = SettingsStore(file).settings.value.assistantLimits
        assertEquals(listOf(90, 15, 3, 5_000), listOf(back.toolTimeoutSeconds, back.tunnelWaitSeconds, back.reconnectMinutes, back.readLinesMax))
    }

    @Test fun `the whole reply is never less than one call plus one model answer`() {
        assertEquals(120_000L, AssistantLimits().replyLimitMs())
        assertEquals(180_000L, AssistantLimits(replySeconds = 30, toolTimeoutSeconds = 100, modelTimeoutSeconds = 80).replyLimitMs())
        assertEquals(1_800_000L, AssistantLimits(replySeconds = 1_800, toolTimeoutSeconds = 10, modelTimeoutSeconds = 10).replyLimitMs())
    }

    @Test fun `a Send reads the owner's limits into the agent's, the provider's token limits kept`() {
        val owner = AssistantLimits(toolTimeoutSeconds = 45, modelTimeoutSeconds = 100, stepsPerReply = 20, callsPerStep = 6, toolOutputChars = 12_000, replyChars = 64_000)
        val agent = AgentLimits(contextTokens = 100_000, outputReserveTokens = 1_000).withOwnerLimits(owner)
        assertEquals(listOf(20L, 6L, 12_000L, 64_000L, 100_000L, 45_000L), listOf(agent.maxSteps.toLong(), agent.maxCallsPerStep.toLong(), agent.maxToolChars.toLong(), agent.maxReplyChars.toLong(), agent.modelTimeoutMs, agent.toolTimeoutMs))
        assertEquals(100_000, agent.contextTokens)
        // At the defaults it is the kernel's own defaults: nothing moved.
        assertEquals(AgentLimits(), AgentLimits().withOwnerLimits(AssistantLimits()))
        // Every key of the agent's range is accepted by the kernel.
        for (spec in LIMITS) for (v in listOf(spec.range.first, spec.range.last)) AgentLimits().withOwnerLimits(spec.set(AssistantLimits(), v))
    }

    @Test fun `the kernel's own defaults in agent-core mirror the owner's defaults, pinned equal (agent-core cannot depend on the app)`() {
        val agent = AgentLimits(); val owner = AssistantLimits()
        assertEquals(owner.stepsPerReply, agent.maxSteps); assertEquals(owner.callsPerStep, agent.maxCallsPerStep)
        assertEquals(owner.toolOutputChars, agent.maxToolChars); assertEquals(owner.replyChars, agent.maxReplyChars)
        assertEquals(owner.modelTimeoutSeconds * 1000L, agent.modelTimeoutMs); assertEquals(owner.toolTimeoutSeconds * 1000L, agent.toolTimeoutMs)
    }

    @Test fun `the summary trigger and the summary size never pass the window together, and the setter makes room`() {
        val d = AssistantLimits()
        assertEquals(95, d.summaryPercent + d.summarySizePercent); assertTrue(validAssistantLimits(d))
        assertFalse(validAssistantLimits(d.copy(summaryPercentSet = 85)))
        assertFalse(validAssistantLimits(d.copy(summarySizePercent = 20)))
        // The screen's limits: each one's highest value leaves room for the other.
        assertEquals(80, d.summaryPercentMax()); assertEquals(15, d.summarySizeMax())
        assertEquals(90, AssistantLimits(summarySizePercent = 5).summaryPercentMax()); assertEquals(40, AssistantLimits().withSummaryPercent(50).summarySizeMax())
        // Raising one makes the other give way; the pair stays valid at every step.
        for (trigger in AssistantLimits.SUMMARY_PERCENT_RANGE step 5) assertTrue(validAssistantLimits(AssistantLimits().withSummaryPercent(trigger)))
        for (size in AssistantLimits.SUMMARY_SIZE_RANGE step 5) assertTrue(validAssistantLimits(AssistantLimits().withSummarySize(size)))
        assertEquals(5, AssistantLimits().withSummaryPercent(90).summarySizePercent); assertEquals(55, AssistantLimits().withSummarySize(40).summaryPercent)
        // A file that breaks it is read with the size giving way; the trigger the owner chose stays.
        val read = SettingsStore(MemoryStorage("""{"assistant_limits":{"summary_percent":85,"summary_size_percent":30}}""")).settings.value.assistantLimits
        assertEquals(85, read.summaryPercent); assertEquals(10, read.summarySizePercent); assertTrue(validAssistantLimits(read))
        // Refused when set through the store.
        val store = SettingsStore(MemoryStorage())
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(d.copy(summaryPercentSet = 90)) }
    }

    @Test fun `the trigger is absent until set, reads as 80 and a stored value of the provider form moves here once`() {
        assertNull(SettingsStore(MemoryStorage("""{"assistant_enabled":true}""")).settings.value.assistantLimits.summaryPercentSet)
        assertEquals(80, SettingsStore(MemoryStorage("""{"assistant_limits":{"summary_size_percent":10}}""")).settings.value.assistantLimits.summaryPercent)
        val file = MemoryStorage()
        val store = SettingsStore(file)
        store.adoptSummaryPercent(60)
        assertEquals(60, SettingsStore(file).settings.value.assistantLimits.summaryPercent)
        // Set by the owner (even to the default): the form's old value is never read again.
        store.adoptSummaryPercent(30)
        assertEquals(60, SettingsStore(file).settings.value.assistantLimits.summaryPercent)
        val explicit = MemoryStorage(); val owner = SettingsStore(explicit); owner.setAssistantLimits(AssistantLimits().withSummaryPercent(80)); owner.adoptSummaryPercent(20)
        assertEquals(80, SettingsStore(explicit).settings.value.assistantLimits.summaryPercent)
        // Old values outside the new range are clamped, and a trigger the default summary size leaves no room for becomes 80 with the size kept at 15.
        for (legacy in listOf(81, 85, 90, 95, 100)) {
            val high = SettingsStore(MemoryStorage()); high.adoptSummaryPercent(legacy)
            val l = high.settings.value.assistantLimits
            assertEquals("$legacy", 80, l.summaryPercent); assertEquals("$legacy", 15, l.summarySizePercent); assertTrue(validAssistantLimits(l))
        }
        for (legacy in listOf(60, 80)) { val ok = SettingsStore(MemoryStorage()); ok.adoptSummaryPercent(legacy); assertEquals(legacy, ok.settings.value.assistantLimits.summaryPercent); assertEquals(15, ok.settings.value.assistantLimits.summarySizePercent) }
        val low = SettingsStore(MemoryStorage()); low.adoptSummaryPercent(1)
        assertEquals(10, low.settings.value.assistantLimits.summaryPercent)
    }
}
