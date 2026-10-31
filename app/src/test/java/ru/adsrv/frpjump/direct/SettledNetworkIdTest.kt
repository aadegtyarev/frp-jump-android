package ru.adsrv.frpjump.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettledNetworkIdTest {
    private val id = SettledNetworkId(settleMs = 1_000)

    @Test
    fun `the first network counts at once`() {
        assertNull(id.observe("wifi:a", 0))
        assertEquals("wifi:a", id.current)
    }

    @Test
    fun `a new network counts once it has held for a second`() {
        id.observe("wifi:a", 0)
        assertEquals(1_000L, id.observe("wifi:b", 10_000))
        assertEquals("wifi:a", id.current)
        assertEquals(400L, id.settle(10_600))
        assertNull(id.settle(11_000))
        assertEquals("wifi:b", id.current)
    }

    @Test
    fun `passing states of a switch do not count, the last one does`() {
        id.observe("wifi:a", 0)
        id.observe("other:x", 10_000)
        id.observe("mobile", 10_300)
        assertEquals("wifi:a", id.current)
        id.settle(11_000)
        assertEquals("wifi:a", id.current)
        id.settle(11_300)
        assertEquals("mobile", id.current)
    }

    @Test
    fun `back to the same network before it settles changes nothing`() {
        id.observe("wifi:a", 0)
        id.observe("wifi:b", 10_000)
        assertNull(id.observe("wifi:a", 10_500))
        assertNull(id.settle(20_000))
        assertEquals("wifi:a", id.current)
    }

    @Test
    fun `losing the network keeps the last one`() {
        id.observe("wifi:a", 0)
        assertNull(id.observe(null, 5_000))
        assertNull(id.settle(60_000))
        assertEquals("wifi:a", id.current)
    }
}
