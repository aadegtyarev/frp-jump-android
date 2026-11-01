package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/contract.md scenario 12: what Settings › «Плагины», a plugin's screen and a connection's sheet show. */
class PluginsOverviewTest {
    private val a = pluginKey("p1", "c1")
    private val b = pluginKey("p1", "c2")
    private val wb = PluginIds.WIREN_BOARD
    private val sc = PluginIds.STATUS_COMMAND

    @Test
    fun `defaults - both on, no exceptions, rows in the contract's order`() {
        val file = PluginsFile()
        assertEquals(PluginsSummary(on = 2, total = 2, anyExceptions = false), pluginsSummary(file))
        assertEquals(listOf(wb, sc), pluginItems(file).map { it.id })
        assertEquals(PluginSource.APP, sourceOf(file, a, wb))
        assertEquals(emptyList<OwnEntry>(), ownEntries(file, wb))
    }

    @Test
    fun `a connection switched off is an exception of that plugin only`() {
        val file = PluginsFile().switched(a, wb, false)
        assertEquals(PluginsSummary(on = 2, total = 2, anyExceptions = true), pluginsSummary(file))
        assertEquals(Exceptions(on = 0, off = 1), pluginItems(file).first { it.id == wb }.exceptions)
        assertEquals(Exceptions(0, 0), pluginItems(file).first { it.id == sc }.exceptions)
        assertEquals(PluginSource.OWN, sourceOf(file, a, wb))
        assertEquals(PluginSource.APP, sourceOf(file, b, wb))
        assertEquals(listOf(OwnEntry(a, "p1", "c1", on = false)), ownEntries(file, wb))
    }

    @Test
    fun `app level turned off - the summary counts it, an own off stays an exception`() {
        val file = PluginsFile().switched(a, wb, false).withApp { it.withOn(wb, false) }
        assertEquals(PluginsSummary(on = 1, total = 2, anyExceptions = true), pluginsSummary(file))
        assertEquals(listOf(OwnEntry(a, "p1", "c1", on = false)), ownEntries(file, wb))
        // Turned on again at app level, the connection is still off.
        val again = file.withApp { it.withOn(wb, true) }
        assertEquals(false, again.isOn(a, wb))
        assertEquals(PluginSource.OWN, sourceOf(again, a, wb))
    }

    @Test
    fun `app level off and one connection on - listed as on`() {
        val file = PluginsFile().withApp { it.withOn(wb, false) }.switched(b, wb, true)
        assertEquals(Exceptions(on = 1, off = 0), pluginItems(file).first { it.id == wb }.exceptions)
        assertEquals(listOf(OwnEntry(b, "p1", "c2", on = true)), ownEntries(file, wb))
    }

    @Test
    fun `as in Settings removes the exception, switching back to the app level too`() {
        val file = PluginsFile().switched(a, wb, false).switched(b, wb, false)
        val followed = file.followingApp(a, wb)
        assertEquals(PluginSource.APP, sourceOf(followed, a, wb))
        assertEquals(listOf(b), ownEntries(followed, wb).map { it.key })
        val back = followed.switched(b, wb, true)
        assertEquals(PluginsSummary(on = 2, total = 2, anyExceptions = false), pluginsSummary(back))
    }

    @Test
    fun `own entries keep the file's order and skip other plugins`() {
        val file = PluginsFile().switched(b, wb, false).switched(a, sc, false).switched(a, wb, false)
        assertEquals(listOf(b, a), ownEntries(file, wb).map { it.key })
        assertEquals(listOf(a), ownEntries(file, sc).map { it.key })
    }
}
