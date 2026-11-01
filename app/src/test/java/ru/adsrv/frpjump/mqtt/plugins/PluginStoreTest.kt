package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class PluginStoreTest {
    private val rule = RuleSpec("home/{x...}/state", "home/{x...}/cmd", listOf("home/x/state"), "home", id = "r1")

    @Test
    fun `a new connection has the plugin on and every preset off`() {
        val settings = PluginStore(MemoryStorage()).get("p", "c").statusCommand
        assertEquals(true, settings.on)
        assertEquals(emptySet<String>(), settings.presets)
        assertEquals(emptyList<RuleSpec>(), settings.rules)
    }

    @Test
    fun `settings come back per connection after a restart`() {
        val storage = MemoryStorage()
        val store = PluginStore(storage)
        store.update("p", "c1") { it.copy(statusCommand = StatusCommandSettings(on = false, presets = setOf(PluginIds.PRESET_TASMOTA), rules = listOf(rule, RuleSpec("a/{d}", id = "r2")))) }
        store.update("p", "c2") { it.copy(statusCommand = it.statusCommand.copy(presets = setOf(PluginIds.PRESET_WIREN_BOARD))) }

        val again = PluginStore(storage)
        val c1 = again.get("p", "c1").statusCommand
        assertEquals(false, c1.on)
        assertEquals(setOf(PluginIds.PRESET_TASMOTA), c1.presets)
        assertEquals(listOf(rule, RuleSpec("a/{d}", id = "r2")), c1.rules)
        assertNull(c1.rules[1].command)
        assertEquals(setOf(PluginIds.PRESET_WIREN_BOARD), again.get("p", "c2").statusCommand.presets)
        assertEquals(true, again.get("p", "c2").statusCommand.on)
        assertEquals(StatusCommandSettings(), again.get("q", "c1").statusCommand)
    }

    @Test
    fun `forget drops a connection, forgetProfile a profile, wipe everything`() {
        val storage = MemoryStorage()
        val store = PluginStore(storage)
        val on = { s: ConnectionPlugins -> s.copy(statusCommand = s.statusCommand.copy(presets = setOf(PluginIds.PRESET_STATE_SET))) }
        store.update("p", "c1", on)
        store.update("p", "c2", on)
        store.update("q", "c1", on)
        store.forget("p", "c1")
        assertEquals(StatusCommandSettings(), PluginStore(storage).get("p", "c1").statusCommand)
        store.forgetProfile("p")
        assertEquals(StatusCommandSettings(), PluginStore(storage).get("p", "c2").statusCommand)
        assertEquals(setOf(PluginIds.PRESET_STATE_SET), PluginStore(storage).get("q", "c1").statusCommand.presets)
        store.wipe()
        assertEquals(StatusCommandSettings(), PluginStore(storage).get("q", "c1").statusCommand)
    }

    @Test
    fun `every change is counted`() {
        val store = PluginStore(MemoryStorage())
        val before = store.changes.value
        store.update("p", "c") { it.copy(statusCommand = it.statusCommand.copy(on = false)) }
        assertEquals(before + 1, store.changes.value)
    }

    @Test
    fun `an unreadable file reads as defaults`() {
        val storage = MemoryStorage()
        storage.write("not json")
        assertEquals(ConnectionPlugins(), PluginStore(storage).get("p", "c"))
    }
}
