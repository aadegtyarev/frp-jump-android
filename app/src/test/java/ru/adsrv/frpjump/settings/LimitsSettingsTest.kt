package ru.adsrv.frpjump.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentLimits
import ru.adsrv.frpjump.assistant.chat.withOwnerLimits
import ru.adsrv.frpjump.ssh.MemoryStorage

/** The one table of the owner's limits: every key's default, range and step, normalized each on its own, and how a Send reads them. */
class LimitsSettingsTest {
    private val approved = mapOf(
        "tool_timeout_s" to Triple(30, 10..300, 5), "model_timeout_s" to Triple(60, 10..600, 10), "reply_s" to Triple(120, 30..1800, 30),
        "steps_per_reply" to Triple(12, 1..50, 1), "calls_per_step" to Triple(4, 1..10, 1), "tool_output_chars" to Triple(8_000, 2_000..32_000, 1_000),
        "reply_chars" to Triple(32_000, 4_000..128_000, 4_000), "waits_at_once" to Triple(3, 1..10, 1), "wait_max_minutes" to Triple(30, 1..120, 1),
        "event_replies" to Triple(5, 1..20, 1), "sessions_at_once" to Triple(3, 1..10, 1), "input_chars" to Triple(2_000, 100..10_000, 100),
        "keys_at_once" to Triple(32, 1..100, 1), "read_lines_max" to Triple(1_000, 100..10_000, 100), "tunnel_wait_s" to Triple(45, 5..120, 5),
        "reconnect_minutes" to Triple(10, 1..60, 1),
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
            val over = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":${spec.range.last + 1},"${control.key}":${control.range.last}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, spec.range.last, spec.get(over)); assertEquals(spec.key, control.range.last, control.get(over))
            val under = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":${spec.range.first - 1}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, spec.range.first, spec.get(under))
            // Malformed (not a number): the default, and only that key.
            val text = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":"many","${control.key}":${control.range.last}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, spec.get(AssistantLimits()), spec.get(text)); assertEquals(spec.key, control.range.last, control.get(text))
            val good = SettingsStore(MemoryStorage("""{"assistant_limits":{"${spec.key}":${spec.range.last}}}""")).settings.value.assistantLimits
            assertEquals(spec.key, spec.range.last, spec.get(good))
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
}
