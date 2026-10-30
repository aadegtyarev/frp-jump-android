package ru.adsrv.frpjump.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileIndexTest {
    private fun profile(id: String, color: String = "blue") = Profile(id, id, color, profileKeyAlias(id))

    private val a = profile("a")
    private val b = profile("b", "green")
    private val c = profile("c", "orange")

    @Test
    fun `an added profile is appended and shown`() {
        val index = ProfileIndex().plus(a).plus(b)
        assertEquals(listOf(a, b), index.profiles)
        assertEquals("b", index.current)
        assertEquals("b", index.plus(c, makeCurrent = false).current)
    }

    @Test
    fun `forgetting the shown profile shows the next one, or the previous one when it was last`() {
        val index = ProfileIndex(listOf(a, b, c), current = "b")
        assertEquals(ProfileIndex(listOf(a, c), current = "c"), index.minus("b"))
        assertEquals("b", index.withCurrent("c").minus("c").current)
        assertEquals("b", index.minus("a").current)
    }

    @Test
    fun `forgetting the last profile leaves none shown`() {
        assertNull(ProfileIndex(listOf(a), current = "a").minus("a").current)
    }

    @Test
    fun `only a listed profile can be shown`() {
        assertEquals("a", ProfileIndex(listOf(a), current = "a").withCurrent("zzz").current)
    }

    @Test
    fun `colours are handed out in order, a freed one first`() {
        assertEquals("blue", nextProfileColor(emptyList()))
        assertEquals("orange", nextProfileColor(listOf(a, b)))
        assertEquals("green", nextProfileColor(listOf(a, c)))
        val all = PROFILE_COLORS.mapIndexed { i, color -> profile("p$i", color) }
        assertEquals(PROFILE_COLORS[0], nextProfileColor(all))
    }

    @Test
    fun `a new profile is named after its server's host`() {
        assertEquals("ikar.example", defaultProfileName("https://ikar.example:8443/"))
        assertEquals("not a url", defaultProfileName("not a url"))
    }
}
