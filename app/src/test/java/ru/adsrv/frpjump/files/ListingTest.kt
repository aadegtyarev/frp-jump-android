package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingTest {
    private fun dir(name: String) = RemoteEntry(name, "/x/$name", EntryType.DIR)
    private fun file(name: String) = RemoteEntry(name, "/x/$name", EntryType.FILE, size = 1)

    @Test
    fun `folders first, then files, each by name ignoring case`() {
        val listing = listOf(file("b.txt"), dir("zeta"), file("A.txt"), dir("Alpha"), file("a.txt"))
        assertEquals(listOf("Alpha", "zeta", "A.txt", "a.txt", "b.txt"), visibleListing(listing, showHidden = true).map { it.name })
    }

    @Test
    fun `a link to a folder sorts with the folders`() {
        val link = RemoteEntry("data", "/x/data", EntryType.DIR, link = LinkTarget("/mnt/data", "/mnt/data"))
        assertEquals(listOf("data", "zeta", "a.txt"), visibleListing(listOf(file("a.txt"), dir("zeta"), link), showHidden = false).map { it.name })
    }

    @Test
    fun `dot names show only with the toggle`() {
        val listing = listOf(file(".bashrc"), dir(".ssh"), file("notes"), dir("www"))
        assertEquals(listOf("www", "notes"), visibleListing(listing, showHidden = false).map { it.name })
        assertEquals(listOf(".ssh", "www", ".bashrc", "notes"), visibleListing(listing, showHidden = true).map { it.name })
        assertTrue(file(".profile").hidden)
        assertFalse(file("profile.").hidden)
    }

    @Test
    fun `a link shows its target as an absolute path`() {
        val relative = linkTarget("/etc/wb-rules/x.js", "../../mnt/data/etc/x.js", broken = false)
        assertEquals("/mnt/data/etc/x.js", relative.path)
        assertEquals("../../mnt/data/etc/x.js", relative.raw)
        assertFalse(relative.broken)
        assertEquals("→ /mnt/data/etc/x.js", linkLabel(relative))
        assertEquals("/mnt/data/etc", linkTarget("/root/etc", "/mnt/data/etc/", broken = false).path)
        assertEquals("/root/sibling", linkTarget("/root/link", "sibling", broken = false).path)
    }

    @Test
    fun `only a file, or a link whose target could not be looked at but is there, can be downloaded`() {
        val unreadable = RemoteEntry("secret", "/root/secret", EntryType.OTHER, link = LinkTarget("/srv/secret", "/srv/secret"))
        val broken = RemoteEntry("old", "/root/old", EntryType.OTHER, link = LinkTarget("/mnt/gone", "/mnt/gone", broken = true))
        val socket = RemoteEntry("sock", "/run/sock", EntryType.OTHER)
        assertTrue(unreadable.transferable)
        assertFalse(broken.transferable)
        assertFalse(socket.transferable)
        assertTrue(file("a").transferable)
    }

    @Test
    fun `a folder link opens at its target, a broken one at itself`() {
        val toDir = RemoteEntry("etc", "/root/etc", EntryType.DIR, link = LinkTarget("../mnt/etc", "/mnt/etc"))
        assertEquals("/mnt/etc", toDir.folderPath)
        assertEquals("/x/plain", dir("plain").folderPath)
    }

    @Test
    fun `go to the target opens a folder link's target, else the folder its target is in`() {
        val toDir = RemoteEntry("etc", "/root/etc", EntryType.DIR, link = LinkTarget("/mnt/data/etc", "/mnt/data/etc"))
        assertEquals("/mnt/data/etc", targetFolder(toDir))
        val toFile = RemoteEntry("x.js", "/etc/x.js", EntryType.FILE, link = LinkTarget("/mnt/data/x.js", "/mnt/data/x.js"))
        assertEquals("/mnt/data", targetFolder(toFile))
        val broken = RemoteEntry("old", "/root/old", EntryType.OTHER, link = LinkTarget("/mnt/gone/dir", "/mnt/gone/dir", broken = true))
        assertEquals("/mnt/gone", targetFolder(broken))
        assertNull(targetFolder(file("plain")))
    }
}
