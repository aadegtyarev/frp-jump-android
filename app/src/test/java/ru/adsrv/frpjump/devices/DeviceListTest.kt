package ru.adsrv.frpjump.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.OwnedDevice
import ru.adsrv.frpjump.ssh.MemoryStorage
import java.time.Instant

class DeviceListTest {
    private val fetchedAt = Instant.parse("2026-10-31T10:00:00Z")
    private val devices = listOf(
        OwnedDevice("wb", enrolledAt = "2026-10-01T00:00:00", lastSeenAt = "2026-10-31T09:59:55", enabled = true),
        OwnedDevice("phone", enrolledAt = "2026-10-01T00:00:00", lastSeenAt = "2026-10-31T10:00:00", enabled = true),
        OwnedDevice("old", enrolledAt = "2026-10-01T00:00:00", lastSeenAt = null, enabled = false),
    )

    @Test
    fun `cache round-trips the list of the same enrollment`() {
        val cache = DeviceListCache(MemoryStorage())
        val list = CachedDeviceList("https://way.example", "phone", fetchedAt.toEpochMilli(), devices)
        cache.save(list)
        assertEquals(list, cache.load("https://way.example", "phone"))
    }

    @Test
    fun `cache of another server or device name is not shown`() {
        val cache = DeviceListCache(MemoryStorage())
        cache.save(CachedDeviceList("https://way.example", "phone", fetchedAt.toEpochMilli(), devices))
        assertNull(cache.load("https://other.example", "phone"))
        assertNull(cache.load("https://way.example", "tablet"))
    }

    @Test
    fun `unreadable or wiped cache is just absent`() {
        assertNull(DeviceListCache(MemoryStorage("{not json")).load("https://way.example", "phone"))
        val cache = DeviceListCache(MemoryStorage())
        cache.save(CachedDeviceList("https://way.example", "phone", fetchedAt.toEpochMilli(), devices))
        cache.wipe()
        assertNull(cache.load("https://way.example", "phone"))
    }

    @Test
    fun `fresh rows skip the phone itself and are live`() {
        val rows = deviceRows(devices, "phone", fetchedAt, fetchedAt, stale = false)
        assertEquals(listOf("wb", "old"), rows.map { it.name })
        assertTrue(rows[0].online)
        assertFalse(rows[0].stale)
        assertTrue(rows[1].disabled)
    }

    @Test
    fun `stale rows keep online as of the fetch but count last seen from now`() {
        val now = fetchedAt.plusSeconds(3600)
        val wb = deviceRows(devices, "phone", fetchedAt, now, stale = true).first()
        assertTrue(wb.online)
        assertTrue(wb.stale)
        assertEquals("1 h ago", wb.lastSeen)
    }
}
