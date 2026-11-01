package ru.adsrv.frpjump.ports

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPortAllocatorTest {
    private val busy = mutableSetOf<Int>()
    private val allocator = LocalPortAllocator(40000..40002) { it !in busy }

    @Test
    fun `a key keeps its port across calls`() {
        val ports = mutableMapOf<String, Int>()
        val first = allocator.assign(ports, "wb:80", probeExisting = true)
        allocator.assign(ports, "wb:22", probeExisting = true)
        assertEquals(first, allocator.assign(ports, "wb:80", probeExisting = true))
        assertEquals(mapOf("wb:80" to 40000, "wb:22" to 40001), ports)
    }

    @Test
    fun `a port promised to another key is not handed out even when free`() {
        val ports = mutableMapOf("old:80" to 40000)
        assertEquals(40001, allocator.assign(ports, "wb:80", probeExisting = true))
    }

    @Test
    fun `busy ports are skipped`() {
        busy += 40000
        assertEquals(40001, allocator.assign(mutableMapOf(), "wb:80", probeExisting = true))
    }

    @Test
    fun `a kept port that became busy moves only when probed`() {
        val ports = mutableMapOf("wb:80" to 40000)
        busy += 40000
        // Held by the running frpc: busy is expected, the port stays.
        assertEquals(40000, allocator.assign(ports, "wb:80", probeExisting = false))
        // Taken by something else before frpc got it: pick another.
        assertEquals(40001, allocator.assign(ports, "wb:80", probeExisting = true))
        assertEquals(40001, ports["wb:80"])
    }

    @Test
    fun `a port outside a narrowed range is replaced`() {
        val ports = mutableMapOf("wb:80" to 50000)
        assertEquals(40000, allocator.assign(ports, "wb:80", probeExisting = false))
    }

    @Test(expected = NoFreePortException::class)
    fun `a full range is an error`() {
        busy += listOf(40000, 40001, 40002)
        allocator.assign(mutableMapOf(), "wb:80", probeExisting = true)
    }
}
