package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class TmuxPickUpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `only this install's sessions are picked up, by number`() {
        val output = listOf(
            "0",
            "work",
            "frpjump-0a1b2c-10",
            // Another phone's.
            "frpjump-ffffff-1",
            "frpjump-0a1b2c-2",
            // Not a name the app makes.
            "frpjump-0a1b2c-x",
            "frpjump-0a1b2c-0",
            "frpjump-0a1b2c-2",
            "frpjump-0a1b2c-1\r",
        ).joinToString("\n")
        assertEquals(listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2", "frpjump-0a1b2c-10"), ownTmuxSessions(output, "0a1b2c"))
        assertEquals(emptyList<String>(), ownTmuxSessions("", "0a1b2c"))
    }

    /** The listing through a real `sh -c`, as sshd runs it. */
    @Test
    fun `the listing through sh, with and without tmux`() {
        assertEquals(listOf("list-sessions", "-F", "#{session_name}"), run(TMUX_LIST_COMMAND, tmux = "printf '%s\\n' \"\$@\"").lines().dropLast(1))
        assertEquals(
            listOf("frpjump-0a1b2c-3"),
            ownTmuxSessions(run(TMUX_LIST_COMMAND, tmux = "printf 'work\\nfrpjump-0a1b2c-3\\n'"), "0a1b2c"),
        )
        // No server running: tmux says so on stderr and fails.
        assertEquals(emptyList<String>(), ownTmuxSessions(run(TMUX_LIST_COMMAND, tmux = "echo 'no server running on /tmp/tmux-0/default' >&2; exit 1"), "0a1b2c"))
        assertEquals(emptyList<String>(), ownTmuxSessions(run(TMUX_LIST_COMMAND, tmux = null), "0a1b2c"))
    }

    @Test
    fun `close all ends each session by its exact name, one gone does not stop the rest`() {
        val names = listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-10")
        assertEquals(
            "tmux kill-session -t '=frpjump-0a1b2c-1'; tmux kill-session -t '=frpjump-0a1b2c-10'",
            tmuxKillAllCommand(names),
        )
        // The first one is gone already (tmux fails for it): the second is still ended.
        val calls = run(tmuxKillAllCommand(names), tmux = "echo \"\$*\"; [ \"\$3\" != '=frpjump-0a1b2c-1' ]")
        assertEquals(listOf("kill-session -t =frpjump-0a1b2c-1", "kill-session -t =frpjump-0a1b2c-10"), calls.lines().dropLast(1))
        assertThrows(IllegalArgumentException::class.java) { tmuxKillAllCommand(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { tmuxKillAllCommand(listOf("work")) }
    }

    @Test
    fun `close all counts the tabs and the sessions no tab holds`() {
        val found = listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2", "frpjump-0a1b2c-5")
        val tabs = listOf(
            CloseAllTab(7, "frpjump-0a1b2c-1", here = true, hidden = false),
            // A plain shell here.
            CloseAllTab(8, null, here = true, hidden = false),
            // Its own close is already waiting: not counted again, its session still ends.
            CloseAllTab(9, "frpjump-0a1b2c-2", here = true, hidden = true),
        )
        val plan = closeAllPlan(tabs, found)
        assertEquals(listOf(7, 8), plan.tabs)
        assertEquals(listOf("frpjump-0a1b2c-5"), plan.orphans)
        assertEquals(found, plan.kill)
        assertEquals(3, plan.count)
        assertEquals(0, closeAllPlan(emptyList(), emptyList()).count)
    }

    @Test
    fun `close all never ends a session a tab that stays holds`() {
        val found = listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2", "frpjump-0a1b2c-3")
        val tabs = listOf(
            CloseAllTab(1, "frpjump-0a1b2c-1", here = true, hidden = false),
            // The same device and user through another port or profile: it stays, so does its session.
            CloseAllTab(2, "frpjump-0a1b2c-2", here = false, hidden = false),
            // Elsewhere but already closing with its own Undo: its session may go.
            CloseAllTab(3, "frpjump-0a1b2c-3", here = false, hidden = true),
        )
        val plan = closeAllPlan(tabs, found)
        assertEquals(listOf(1), plan.tabs)
        assertEquals(emptyList<String>(), plan.orphans)
        assertEquals(listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-3"), plan.kill)
    }

    @Test
    fun `a kill is checked by what is still listed, not by the exit status`() {
        val names = listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2")
        assertEquals(
            "tmux kill-session -t '=frpjump-0a1b2c-1'; tmux kill-session -t '=frpjump-0a1b2c-2'; tmux list-sessions -F '#{session_name}'",
            tmuxKillAllAndListCommand(names),
        )
        // All gone: the server exits, the listing fails and prints nothing.
        assertEquals(emptyList<String>(), stillRunning(run(tmuxKillAllAndListCommand(names), tmux = "[ \"\$1\" = kill-session ] || exit 1"), names))
        // One did not end: it is still listed.
        val stuck = "if [ \"\$1\" = list-sessions ]; then printf 'work\\nfrpjump-0a1b2c-2\\n'; fi"
        assertEquals(listOf("frpjump-0a1b2c-2"), stillRunning(run(tmuxKillAllAndListCommand(names), tmux = stuck), names))
    }

    @Test
    fun `a pick-up takes only names no tab holds and none handed out to another`() {
        val names = TmuxNames(MemoryStorage(), Random(1))
        val i = names.install
        val claims = PickUpClaims(names)
        val found = listOf("frpjump-$i-1", "frpjump-$i-2", "frpjump-$i-3")
        val first = TabTmux(names)
        // -1 is a tab's already (opened meanwhile, or alive since before).
        assertEquals(listOf("frpjump-$i-2", "frpjump-$i-3"), claims.claim(found, { setOf("frpjump-$i-1") }, first::adopt))
        assertEquals("frpjump-$i-2", first.assigned)
        // Another first tab at once: -2 is held by the tab, -3 is handed out and its tab not made yet.
        val second = TabTmux(names)
        assertEquals(emptyList<String>(), claims.claim(found, { setOf("frpjump-$i-1", "frpjump-$i-2") }, second::adopt))
        assertEquals(null, second.assigned)
        // -3 not wanted after all (its first tab was closed): free again.
        claims.release(listOf("frpjump-$i-3"))
        assertEquals(listOf("frpjump-$i-3"), claims.claim(found, { setOf("frpjump-$i-1", "frpjump-$i-2") }, second::adopt))
        // The counter moved past what was picked up.
        assertEquals("frpjump-$i-4", names.next())
        // A tab that has its name already takes none.
        assertEquals(emptyList<String>(), claims.claim(listOf("frpjump-$i-9"), { emptySet() }, first::adopt))
    }

    @Test
    fun `new tabs are numbered past the sessions picked up`() {
        val storage = MemoryStorage()
        val names = TmuxNames(storage, Random(1))
        val install = names.install
        assertEquals("frpjump-$install-1", names.next())
        // Found on the device above the counter (it was not saved then); another install's is no reason to move.
        names.reserve(listOf("frpjump-$install-9", "frpjump-$install-4", "frpjump-ffffff-50"))
        assertEquals("frpjump-$install-10", names.next())
        // Below the counter: nothing changes.
        names.reserve(listOf("frpjump-$install-3"))
        assertEquals("frpjump-$install-11", TmuxNames(storage, Random(2)).next())
    }

    @Test
    fun `a tab takes a picked-up name only before it has one`() {
        val names = TmuxNames(MemoryStorage(), Random(1))
        val tab = TabTmux(names)
        assertTrue(tab.adopt("frpjump-${names.install}-4"))
        assertEquals("frpjump-${names.install}-4", tab.name())
        assertFalse(tab.adopt("frpjump-${names.install}-5"))
        assertEquals("frpjump-${names.install}-4", tab.assigned)
    }

    /**
     * Runs [command] with `sh -c` and a PATH holding only a `tmux` script with body [tmux] (none when null); its stdout.
     * Only that folder: a machine with a real tmux (CI runners) must not answer for "no tmux".
     */
    private fun run(command: String, tmux: String?): String {
        val bin = tmp.newFolder()
        if (tmux != null) {
            File(bin, "tmux").apply {
                writeText("#!/bin/sh\n$tmux\n")
                setExecutable(true)
            }
        }
        val process = ProcessBuilder("/bin/sh", "-c", command).apply { environment()["PATH"] = bin.path }.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }
}
