package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class WirenBoardSettingsTest {
    @Test
    fun `a new connection has the plugin on and no tab remembered`() {
        val wb = PluginStore(MemoryStorage()).get("p", "c").wirenBoard
        assertTrue(wb.on)
        assertFalse(wb.tabShown)
    }

    @Test
    fun `the tab appearing turns the Wiren Board preset on, once`() {
        val first = wbTabAppeared(ConnectionPlugins())
        assertTrue(first.wirenBoard.tabShown)
        assertEquals(setOf(PluginIds.PRESET_WIREN_BOARD), first.statusCommand.presets)
        assertTrue(first.statusCommand.wirenBoardTabSeen)
        // Turned off by hand afterwards: it stays off when the tab appears again.
        val off = first.copy(statusCommand = first.statusCommand.copy(presets = emptySet()))
        assertEquals(off, wbTabAppeared(off))
    }

    @Test
    fun `with the first plugin off the tab appearing does not turn the preset on, nor does a later appearance`() {
        val plugins = ConnectionPlugins(statusCommand = StatusCommandSettings(on = false))
        val after = wbTabAppeared(plugins)
        assertTrue(after.wirenBoard.tabShown)
        assertEquals(emptySet<String>(), after.statusCommand.presets)
        assertTrue(after.statusCommand.wirenBoardTabSeen)
        // The owner turns the first plugin on afterwards: the tab appearing again leaves the preset off.
        val turnedOn = after.copy(statusCommand = after.statusCommand.copy(on = true))
        assertEquals(emptySet<String>(), wbTabAppeared(turnedOn).statusCommand.presets)
    }

    @Test
    fun `other presets stay as they are`() {
        val plugins = ConnectionPlugins(statusCommand = StatusCommandSettings(presets = setOf(PluginIds.PRESET_TASMOTA)))
        assertEquals(setOf(PluginIds.PRESET_TASMOTA, PluginIds.PRESET_WIREN_BOARD), wbTabAppeared(plugins).statusCommand.presets)
    }

    @Test
    fun `a session without meta forgets the tab, and the settings survive a restart`() {
        val storage = MemoryStorage()
        PluginStore(storage).update("p", "c", ::wbTabAppeared)
        assertTrue(PluginStore(storage).get("p", "c").wirenBoard.tabShown)
        PluginStore(storage).update("p", "c", ::wbTabAbsent)
        val again = PluginStore(storage).get("p", "c")
        assertFalse(again.wirenBoard.tabShown)
        // The preset turned on stays on.
        assertTrue(PluginIds.PRESET_WIREN_BOARD in again.statusCommand.presets)
        PluginStore(storage).update("p", "c") { it.copy(wirenBoard = it.wirenBoard.copy(on = false)) }
        assertFalse(PluginStore(storage).get("p", "c").wirenBoard.on)
    }
}
