package ru.adsrv.frpjump.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

/** The assistant's owner-set limits: defaults, ranges, persistence, old files, and what each Send captures. */
class AssistantLimitsTest {
    @Test fun `defaults and ranges are the approved ones`() {
        val d = AssistantLimits()
        assertEquals(listOf(3, 30, 5), listOf(d.waitsAtOnce, d.waitMaxMinutes, d.eventReplies))
        assertEquals(listOf(1..10, 1..120, 1..20), listOf(AssistantLimits.WAITS_AT_ONCE_RANGE, AssistantLimits.WAIT_MAX_MINUTES_RANGE, AssistantLimits.EVENT_REPLIES_RANGE))
        assertTrue(validAssistantLimits(d))
        for (bad in listOf(d.copy(waitsAtOnce = 0), d.copy(waitsAtOnce = 11), d.copy(waitMaxMinutes = 0), d.copy(waitMaxMinutes = 121),
            d.copy(eventReplies = 0), d.copy(eventReplies = 21))) assertFalse(bad.toString(), validAssistantLimits(bad))
        for (edge in listOf(d.copy(waitsAtOnce = 1), d.copy(waitsAtOnce = 10), d.copy(waitMaxMinutes = 120), d.copy(eventReplies = 20))) assertTrue(validAssistantLimits(edge))
        assertFalse(AppSettings().assistantWaits)
    }

    @Test fun `a limit is saved, read back, and reaches the next Send's availability`() {
        val file = MemoryStorage()
        val store = SettingsStore(file)
        assertEquals(AssistantLimits(), store.assistantAvailability.limits)
        store.setAssistantLimits(AssistantLimits(waitsAtOnce = 7, waitMaxMinutes = 90, eventReplies = 2))
        assertEquals(7, store.assistantAvailability.limits.waitsAtOnce)
        val stored = Json.parseToJsonElement(file.text!!).jsonObject.getValue("assistant_limits").jsonObject
        assertEquals(JsonPrimitive(90), stored["wait_max_minutes"]); assertEquals(null, stored["after_input_seconds"])
        assertEquals(AssistantLimits(7, 90, 2), SettingsStore(file).settings.value.assistantLimits)
        // The wait switch is saved with the other tool switches.
        store.setAssistantTool(ru.adsrv.frpjump.assistant.AssistantToolId.WAIT_FOR, true)
        assertTrue(SettingsStore(file).settings.value.assistantWaits)
        assertTrue(SettingsStore(file).assistantAvailability.tools.waits)
    }

    @Test fun `an out-of-range limit is refused and nothing is written`() {
        val file = MemoryStorage()
        val store = SettingsStore(file)
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(AssistantLimits(waitsAtOnce = 11)) }
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(AssistantLimits(eventReplies = 21)) }
        assertEquals(AssistantLimits(), store.settings.value.assistantLimits)
        assertEquals(null, file.text)
    }

    @Test fun `an old file without the keys keeps the defaults, and one bad value resets only itself`() {
        val old = SettingsStore(MemoryStorage("""{"assistant_enabled":true,"assistant_search":true}"""))
        assertEquals(AssistantLimits(), old.settings.value.assistantLimits)
        assertTrue(old.settings.value.assistantEnabled && old.settings.value.assistantSearch)
        assertFalse(old.settings.value.assistantWaits)
        val mixed = SettingsStore(MemoryStorage("""{"assistant_limits":{"waits_at_once":4,"wait_max_minutes":500,"event_replies":"many"},"assistant_waits":true,"assistant_search":true}"""))
        assertEquals(AssistantLimits(waitsAtOnce = 4, waitMaxMinutes = 30, eventReplies = 5), mixed.settings.value.assistantLimits)
        assertTrue(mixed.settings.value.assistantWaits && mixed.settings.value.assistantSearch)
        // A file written by an earlier build of this change may still carry the removed key: it is ignored.
        assertEquals(AssistantLimits(), SettingsStore(MemoryStorage("""{"assistant_limits":{"after_input_seconds":15}}""")).settings.value.assistantLimits)
        // Not an object at all.
        assertEquals(AssistantLimits(), SettingsStore(MemoryStorage("""{"assistant_limits":7}""")).settings.value.assistantLimits)
    }

    @Test fun `the assistant's sessions at once default to 3, within 1 to 10, each read on its own`() {
        assertEquals(3, AssistantLimits().sessionsAtOnce); assertEquals(1..10, AssistantLimits.SESSIONS_AT_ONCE_RANGE)
        assertTrue(validAssistantLimits(AssistantLimits(sessionsAtOnce = 1))); assertTrue(validAssistantLimits(AssistantLimits(sessionsAtOnce = 10)))
        assertFalse(validAssistantLimits(AssistantLimits(sessionsAtOnce = 0))); assertFalse(validAssistantLimits(AssistantLimits(sessionsAtOnce = 11)))
        // Files written before the limit existed read as the default, and a bad value resets only itself.
        assertEquals(3, SettingsStore(MemoryStorage("""{"assistant_limits":{"waits_at_once":4}}""")).settings.value.assistantLimits.sessionsAtOnce)
        val bad = SettingsStore(MemoryStorage("""{"assistant_limits":{"waits_at_once":4,"sessions_at_once":99}}""")).settings.value.assistantLimits
        assertEquals(AssistantLimits(waitsAtOnce = 4), bad)
        assertEquals(7, SettingsStore(MemoryStorage("""{"assistant_limits":{"sessions_at_once":7}}""")).settings.value.assistantLimits.sessionsAtOnce)
    }
}
