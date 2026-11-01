package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/contract.md scenario 12, "The rule: where a plugin is on", by its table. */
class PluginResolveTest {
    private data class Row(val app: Boolean, val own: Boolean?, val resolved: Boolean, val exception: Boolean)

    private val table = listOf(
        Row(app = true, own = null, resolved = true, exception = false),
        Row(app = true, own = false, resolved = false, exception = true),
        Row(app = true, own = true, resolved = true, exception = true),
        Row(app = false, own = null, resolved = false, exception = false),
        Row(app = false, own = true, resolved = true, exception = true),
        Row(app = false, own = false, resolved = false, exception = true),
    )

    @Test
    fun `resolve and exceptions follow the table`() {
        table.forEach { row ->
            assertEquals(row.toString(), row.resolved, resolve(row.app, row.own))
            val counted = exceptions(mapOf("c" to row.own))
            assertEquals(row.toString(), row.exception, counted.any)
            assertEquals(row.toString(), if (row.own == true) 1 else 0, counted.on)
            assertEquals(row.toString(), if (row.own == false) 1 else 0, counted.off)
        }
    }

    @Test
    fun `exceptions are counted over connections by value`() {
        assertEquals(Exceptions(on = 2, off = 1), exceptions(mapOf("a" to true, "b" to null, "c" to false, "d" to true)))
        assertEquals(Exceptions(0, 0), exceptions(emptyMap<String, Boolean?>()))
    }

    @Test
    fun `a switch keeps an own setting only when it differs from the app level`() {
        assertNull(ownAfterSwitch(moved = true, app = true))
        assertNull(ownAfterSwitch(moved = false, app = false))
        assertEquals(false, ownAfterSwitch(moved = false, app = true))
        assertEquals(true, ownAfterSwitch(moved = true, app = false))
    }

    @Test
    fun `an own off survives the app level turned off and on again`() {
        val key = pluginKey("p", "c")
        val id = PluginIds.WIREN_BOARD
        var f = PluginsFile().switched(key, id, on = false)
        assertFalse(f.isOn(key, id))
        f = f.withApp { it.withOn(id, false) }
        // Equal to the app level now, still an exception.
        assertEquals(Exceptions(on = 0, off = 1), f.exceptionsOf(id))
        f = f.withApp { it.withOn(id, true) }
        assertFalse(f.isOn(key, id))
        assertTrue(f.isOn(pluginKey("p", "other"), id))
        // «Как в настройках».
        f = f.followingApp(key, id)
        assertTrue(f.isOn(key, id))
        assertEquals(PluginsFile(), f)
    }

    @Test
    fun `a connection on with the app level off has the plugin on there only`() {
        val id = PluginIds.WIREN_BOARD
        val f = PluginsFile().withApp { it.withOn(id, false) }.switched(pluginKey("p", "wb"), id, on = true)
        assertTrue(f.isOn(pluginKey("p", "wb"), id))
        assertFalse(f.isOn(pluginKey("p", "home"), id))
        assertEquals(Exceptions(on = 1, off = 0), f.exceptionsOf(id))
        // The other plugin is untouched.
        assertTrue(f.isOn(pluginKey("p", "home"), PluginIds.STATUS_COMMAND))
    }

    @Test
    fun `the Wiren Board preset turns on once for the app, only where Status and command topics is on`() {
        val off = pluginKey("p", "off")
        val on = pluginKey("p", "on")
        var f = PluginsFile().switched(off, PluginIds.STATUS_COMMAND, on = false)
        f = f.wbTabAppeared(off)
        assertTrue(f.forConnection(off).wbTabShown)
        assertEquals(emptySet<String>(), f.app.plugins.statusCommand.presets)
        f = f.wbTabAppeared(on)
        assertEquals(setOf(PluginIds.PRESET_WIREN_BOARD), f.app.plugins.statusCommand.presets)
        assertTrue(f.app.plugins.statusCommand.wirenBoardPresetAutoDone)
        // Turned off by hand: it stays off.
        f = f.withApp { it.copy(statusCommand = it.statusCommand.copy(presets = emptySet())) }
        assertEquals(f, f.wbTabAppeared(on))
        // No meta in a whole tree: the next session opens on Tree; the entry goes when nothing else is kept.
        assertEquals(emptyMap<String, ConnectionEntry>(), f.wbTabAbsent(on).withoutConnection(off).connections)
    }
}
