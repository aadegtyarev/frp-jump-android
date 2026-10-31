package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class ListingCacheTest {
    private val files = mutableMapOf<String, MemoryStorage>()
    private val cache = ListingCache({ profile, key -> files.getOrPut("$profile/$key") { MemoryStorage() } }, now = { 1_000L })

    private val entries = listOf(
        RemoteEntry("etc", "/root/etc", EntryType.DIR, modified = 10, link = LinkTarget("/mnt/data/etc", "/mnt/data/etc")),
        RemoteEntry("x.js", "/root/x.js", EntryType.FILE, size = 42, modified = 20),
    )

    @Test
    fun `a saved listing reads back with when it was seen`() {
        cache.save("p1", "wb-1", 22, "/root", entries)
        val read = cache.load("p1", "wb-1", 22, "/root")
        assertEquals(entries, read?.entries)
        assertEquals(1_000L, read?.savedAt)
        assertEquals("/root", read?.path)
    }

    @Test
    fun `each profile, device, port and folder has its own`() {
        cache.save("p1", "wb-1", 22, "/root", entries)
        assertNull(cache.load("p2", "wb-1", 22, "/root"))
        assertNull(cache.load("p1", "wb-2", 22, "/root"))
        assertNull(cache.load("p1", "wb-1", 2222, "/root"))
        assertNull(cache.load("p1", "wb-1", 22, "/root/etc"))
        assertNotEquals(ListingCache.key("wb-1", 22, "/root"), ListingCache.key("wb-1", 22, "/root/"))
    }

    @Test
    fun `an unreadable file counts as no listing`() {
        files["p1/" + ListingCache.key("wb-1", 22, "/root")] = MemoryStorage("{not json")
        assertNull(cache.load("p1", "wb-1", 22, "/root"))
    }

    @Test
    fun `the home folder is remembered per user`() {
        cache.saveHome("p1", "wb-1", 22, "root", "/root")
        cache.saveHome("p1", "wb-1", 22, "pi", "/home/pi")
        assertEquals("/root", cache.loadHome("p1", "wb-1", 22, "root"))
        assertEquals("/home/pi", cache.loadHome("p1", "wb-1", 22, "pi"))
        assertNull(cache.loadHome("p1", "wb-1", 22, "nobody"))
    }

    @Test
    fun `a huge folder is cut and marked so, not refused`() {
        val many = (1..ListingCache.MAX_ENTRIES + 10).map { RemoteEntry("f$it", "/d/f$it", EntryType.FILE) }
        cache.save("p1", "wb-1", 22, "/d", many)
        val read = cache.load("p1", "wb-1", 22, "/d")
        assertEquals(ListingCache.MAX_ENTRIES, read?.entries?.size)
        assertEquals(true, read?.truncated)
        cache.save("p1", "wb-1", 22, "/root", entries)
        assertEquals(false, cache.load("p1", "wb-1", 22, "/root")?.truncated)
    }
}
