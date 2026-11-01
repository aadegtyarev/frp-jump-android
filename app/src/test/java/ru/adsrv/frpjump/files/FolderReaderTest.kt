package ru.adsrv.frpjump.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderReaderTest {
    @Test
    fun `a folder entered through a link is read where it really is, and links in it resolve from there`() = runBlocking {
        val fs = FakeFs()
            .file("/mnt/data/conf/x.js", "rules")
            .link("/mnt/data/etc/x.js", "../conf/x.js")
            .link("/root/etc", "/mnt/data/etc")
        val folder = readFolder(fs, "/root/etc")
        assertEquals("/mnt/data/etc", folder.path)
        val x = folder.entries.single()
        assertEquals("/mnt/data/etc/x.js", x.path)
        assertEquals("/mnt/data/conf/x.js", x.link?.path)
        assertEquals(EntryType.FILE, x.type)
        assertFalse(x.link!!.broken)
    }

    @Test
    fun `dot is the home folder`() = runBlocking {
        val folder = readFolder(FakeFs(home = "/home/pi").file("/home/pi/notes", "n"), ".")
        assertEquals("/home/pi", folder.path)
        assertEquals(listOf("notes"), folder.entries.map { it.name })
    }

    @Test
    fun `only a missing target makes a link broken, no permission or a loop does not`() = runBlocking {
        val fs = FakeFs()
            .dir("/srv/secret")
            .link("/root/gone", "/nowhere")
            .link("/root/secret", "/srv/secret")
            .link("/root/loop", "loop")
        fs.denied += "/root/secret"
        val byName = readFolder(fs, "/root").entries.associateBy { it.name }
        assertTrue(byName.getValue("gone").link!!.broken)
        assertFalse(byName.getValue("secret").link!!.broken)
        assertEquals(EntryType.OTHER, byName.getValue("secret").type)
        assertTrue(byName.getValue("secret").transferable)
        assertFalse(byName.getValue("loop").link!!.broken)
    }

    @Test
    fun `links are looked into several at a time, never more than the limit`() = runBlocking {
        val fs = FakeFs().dir("/data")
        repeat(30) { fs.link("/root/l$it", "/data") }
        val folder = readFolder(fs, "/root", parallel = 8)
        assertEquals(30, folder.entries.count { it.isDir })
        assertTrue("at most 8: ${fs.mostStatsAtOnce.get()}", fs.mostStatsAtOnce.get() in 2..8)
    }

    @Test
    fun `mode and owner come through, a link's from its target`() = runBlocking {
        val fs = FakeFs()
            .file("/root/run.sh", "#!/bin/sh")
            .file("/srv/data.bin", "x")
            .link("/root/data", "/srv/data.bin")
        fs.owners["/root/run.sh"] = Triple(0x1ED, 0L, 0L)
        fs.owners["/srv/data.bin"] = Triple(0x1A4, 1000L, 4294967294L)
        fs.owners["/root/data"] = Triple(0x1FF, 5L, 5L)
        val byName = readFolder(fs, "/root").entries.associateBy { it.name }
        val run = byName.getValue("run.sh")
        assertEquals(Triple(0x1ED, 0L, 0L), Triple(run.mode, run.uid, run.gid))
        val data = byName.getValue("data")
        assertEquals(Triple(0x1A4, 1000L, 4294967294L), Triple(data.mode, data.uid, data.gid))
    }

    @Test
    fun `no mode or owner from the server stays unknown`() = runBlocking {
        val entry = readFolder(FakeFs().file("/root/a", "a"), "/root").entries.single()
        assertEquals(Triple(null, null, null), Triple(entry.mode, entry.uid, entry.gid))
    }
}
