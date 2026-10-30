package ru.adsrv.frpjump.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.profiles.Profile
import ru.adsrv.frpjump.profiles.ProfileIndex
import ru.adsrv.frpjump.profiles.profileKeyAlias

class ProfileItemsTest {
    private fun profile(id: String, name: String, color: String) = Profile(id, name, color, profileKeyAlias(id))

    private val work = profile("w", "Work", "green")
    private val home = profile("h", "home", "orange")
    private val client = profile("c", "Client", "purple")
    private val index = ProfileIndex(listOf(work, home, client), current = "h")

    @Test
    fun `items keep the owner's order and mark the shown profile`() {
        val items = profileItems(index, emptyMap(), emptySet(), emptyMap())
        assertEquals(listOf("w", "h", "c"), items.map { it.id })
        assertEquals(listOf(false, true, false), items.map { it.current })
        assertEquals(listOf("green", "orange", "purple"), items.map { it.color })
    }

    @Test
    fun `each item carries its own tunnels, server and removal`() {
        val server = ProfileServer("way.example", "phone")
        val items = profileItems(index, mapOf("w" to 2), setOf("c"), mapOf("w" to server, "h" to null))
        val (w, h, c) = items
        assertEquals(2, w.tunnels)
        assertEquals(0, h.tunnels)
        assertEquals(server, w.server)
        assertEquals(null, h.server)
        assertTrue(c.removed)
        assertFalse(w.removed)
    }

    @Test
    fun `only removed profiles that are not shown get the line on the device list`() {
        val items = profileItems(index, emptyMap(), setOf("h", "c"), emptyMap())
        assertEquals(listOf("c"), removedElsewhere(items).map { it.id })
        assertTrue(removedElsewhere(profileItems(index, emptyMap(), setOf("h"), emptyMap())).isEmpty())
    }

    @Test
    fun `the tile letter is the name's first, upper case`() {
        val items = profileItems(index, emptyMap(), emptySet(), emptyMap())
        assertEquals(listOf("W", "H", "C"), items.map { it.letter })
        assertEquals("?", items[0].copy(name = " ").letter)
    }

    @Test
    fun `a server not read yet is loading, not missing`() {
        val items = profileItems(index, emptyMap(), emptySet(), mapOf("w" to null))
        assertTrue(items[0].serverLoaded)
        assertFalse(items[1].serverLoaded)
    }

    @Test
    fun `servers are read once per profile, again only without an enrollment`() {
        val known = mapOf("w" to ProfileServer("way.example", "phone"), "h" to null)
        assertEquals(setOf("h", "c"), serversToLoad(setOf("w", "h", "c"), known))
        assertTrue(serversToLoad(setOf("w"), known).isEmpty())
    }
}
