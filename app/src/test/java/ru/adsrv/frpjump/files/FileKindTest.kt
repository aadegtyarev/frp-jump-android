package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** The kind a row's icon shows. */
class FileKindTest {
    private fun dir(name: String, link: LinkTarget? = null) = RemoteEntry(name, "/x/$name", EntryType.DIR, link = link, mode = 0b111_101_101)
    private fun file(name: String, mode: Int? = 0b110_100_100, link: LinkTarget? = null) = RemoteEntry(name, "/x/$name", EntryType.FILE, mode = mode, link = link)

    @Test
    fun `folder, file and executable`() {
        assertEquals(FileKind.FOLDER, fileKind(dir("etc")))
        assertEquals(FileKind.FILE, fileKind(file("notes.txt")))
        assertEquals(FileKind.FILE, fileKind(file("notes.txt", mode = null)))
        assertEquals(FileKind.EXECUTABLE, fileKind(file("run.sh", mode = 0b111_101_101)))
        // Any x bit: owner, group or others.
        assertEquals(FileKind.EXECUTABLE, fileKind(file("tool", mode = 0b110_100_101)))
        assertEquals(FileKind.EXECUTABLE, fileKind(file("tool", mode = 0b110_101_100)))
    }

    @Test
    fun `archives by name, ignoring case, double extensions too`() {
        for (name in listOf("a.zip", "a.tar", "a.gz", "a.tgz", "a.bz2", "a.xz", "a.zst", "a.7z", "a.rar", "a.deb", "a.tar.gz", "a.tar.zst", "a.tar.br", "BACKUP.TAR.GZ", "Photos.ZIP")) {
            assertEquals(name, FileKind.ARCHIVE, fileKind(file(name)))
        }
        for (name in listOf("a.txt", "zip", ".gz", "tar.txt", "a.gzip.txt", "readme")) {
            assertEquals(name, FileKind.FILE, fileKind(file(name)))
        }
    }

    @Test
    fun `an archive stays an archive with an execute bit`() {
        assertEquals(FileKind.ARCHIVE, fileKind(file("fw.tar.gz", mode = 0b111_111_111)))
    }

    @Test
    fun `a folder named like an archive stays a folder`() {
        assertEquals(FileKind.FOLDER, fileKind(dir("old.tar.gz")))
        assertEquals(FileKind.FOLDER, fileKind(dir("unpacked.zip")))
    }

    @Test
    fun `links take their target's kind, a broken one is broken`() {
        val target = LinkTarget("../data", "/data", broken = false)
        assertEquals(FileKind.FOLDER, fileKind(dir("data", link = target)))
        assertEquals(FileKind.FILE, fileKind(file("conf", link = target)))
        assertEquals(FileKind.ARCHIVE, fileKind(file("latest.tgz", link = target)))
        val broken = RemoteEntry("gone", "/x/gone", EntryType.OTHER, link = LinkTarget("nowhere", "/x/nowhere", broken = true))
        assertEquals(FileKind.BROKEN_LINK, fileKind(broken))
        // Not broken but not looked at (no permission, a loop).
        assertEquals(FileKind.SPECIAL, fileKind(RemoteEntry("loop", "/x/loop", EntryType.OTHER, link = target)))
    }

    @Test
    fun `devices and sockets are special`() {
        assertEquals(FileKind.SPECIAL, fileKind(RemoteEntry("sda", "/dev/sda", EntryType.OTHER, mode = 0b110_110_000)))
    }
}
