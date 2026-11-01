package ru.adsrv.frpjump.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.files.EntryType
import ru.adsrv.frpjump.files.LinkTarget
import ru.adsrv.frpjump.files.RemoteEntry

class FileSelectionTest {
    private fun file(name: String, dir: String = "/root") = RemoteEntry(name, "$dir/$name", EntryType.FILE, size = 1)
    private fun folder(name: String, dir: String = "/root") = RemoteEntry(name, "$dir/$name", EntryType.DIR)

    private val a = file("a.txt")
    private val b = file("b.txt")
    private val etc = folder("etc")
    private val broken = RemoteEntry("gone", "/root/gone", EntryType.OTHER, link = LinkTarget("x", "/root/x", broken = true))
    private val shown = listOf(etc, a, b, broken)

    @Test
    fun `a long press starts it, taps add and remove, the last one taken ends it`() {
        val started = FileSelection().toggle("/root", a)
        assertTrue(started.active)
        assertEquals(1, started.count)
        val two = started.toggle("/root", b)
        assertEquals(setOf(a.path, b.path), two.paths)
        val one = two.toggle("/root", a)
        assertEquals(setOf(b.path), one.paths)
        assertFalse(one.toggle("/root", b).active)
    }

    @Test
    fun `a tap in another folder starts afresh`() {
        val here = FileSelection().toggle("/root", a)
        val there = here.toggle("/etc", file("hosts", "/etc"))
        assertEquals("/etc", there.folder)
        assertEquals(setOf("/etc/hosts"), there.paths)
    }

    @Test
    fun `select all takes every entry shown, folders too`() {
        val all = FileSelection().toggle("/root", a).selectAll("/root", shown)
        assertEquals(shown.map { it.path }.toSet(), all.paths)
        assertEquals(shown, all.picked(shown))
    }

    @Test
    fun `a new listing of the same folder keeps what is still there`() {
        val sel = FileSelection().selectAll("/root", listOf(a, b))
        assertEquals(setOf(b.path), sel.keepIn("/root", listOf(etc, b)).paths)
        assertFalse(sel.keepIn("/root", listOf(etc)).active)
    }

    @Test
    fun `another folder ends it`() {
        val sel = FileSelection().toggle("/root", a)
        assertFalse(sel.keepIn("/etc", listOf(a)).active)
        // Not selecting: nothing changes.
        assertEquals(FileSelection(), FileSelection().keepIn("/etc", listOf(a)))
    }

    @Test
    fun `picked keeps the list's order`() {
        val sel = FileSelection().toggle("/root", b).toggle("/root", etc).toggle("/root", a)
        assertEquals(listOf(etc, a, b), sel.picked(shown))
    }

    @Test
    fun `download and share need a file among the picked`() {
        assertEquals(listOf(SelectionAction.DOWNLOAD, SelectionAction.SHARE), selectionActions(listOf(etc, a)))
        assertEquals(emptyList<SelectionAction>(), selectionActions(listOf(etc, broken)))
        assertEquals(emptyList<SelectionAction>(), selectionActions(emptyList()))
    }

    @Test
    fun `an action takes the files and counts what it leaves out`() {
        assertEquals(listOf(a, b) to 2, transferablePart(shown))
        assertEquals(listOf(a) to 0, transferablePart(listOf(a)))
    }
}
