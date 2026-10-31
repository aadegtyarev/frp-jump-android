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
    fun `close all counts the tabs and the sessions no tab holds, and ends every session found`() {
        val found = listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2", "frpjump-0a1b2c-5")
        // Tab 7 holds -1, a hidden tab holds -2 (its close is already waiting), tab 8 is a plain shell.
        val plan = closeAllPlan(listOf(7, 8), setOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-2"), found)
        assertEquals(listOf(7, 8), plan.tabs)
        assertEquals(listOf("frpjump-0a1b2c-5"), plan.orphans)
        assertEquals(found, plan.kill)
        assertEquals(3, plan.count)
        assertEquals(0, closeAllPlan(emptyList(), emptySet(), emptyList()).count)
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
