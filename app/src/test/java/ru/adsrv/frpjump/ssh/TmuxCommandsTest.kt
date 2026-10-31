package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TmuxCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val name = "frpjump-0a1b2c-7"

    @Test
    fun `the commands as sent to the device`() {
        assertEquals(
            "exec tmux -u new-session -A -D -s 'frpjump-0a1b2c-7' ';' set-option -t '=frpjump-0a1b2c-7:' status off ';' set-option -t '=frpjump-0a1b2c-7:' mouse on",
            tmuxAttachCommand(name),
        )
        assertEquals("tmux kill-session -t '=frpjump-0a1b2c-7'", tmuxKillCommand(name))
        assertEquals(
            "command -v tmux >/dev/null && echo frpjump-tmux && tmux has-session -t '=frpjump-0a1b2c-7' && echo frpjump-attach",
            tmuxProbeCommand(name),
        )
        assertEquals(
            "tmux if-shell -F -t '=frpjump-0a1b2c-7:' '#{pane_in_mode}' 'send-keys -t =frpjump-0a1b2c-7: -X cancel'",
            tmuxLeaveModeCommand(name),
        )
    }

    @Test
    fun `only the app's own names make commands`() {
        assertTrue(isTmuxName("frpjump-abcdef-1"))
        assertTrue(isTmuxName("frpjump-000000-123"))
        for (bad in listOf("frpjump-abcdef-0", "frpjump-ABCDEF-1", "frpjump-abcde-1", "work", "frpjump-abcdef-1'; rm -rf ~", "frpjump-abcdef-1 x", "")) {
            assertFalse(bad, isTmuxName(bad))
            assertThrows(IllegalArgumentException::class.java) { tmuxKillCommand(bad) }
            assertThrows(IllegalArgumentException::class.java) { tmuxAttachCommand(bad) }
        }
    }

    @Test
    fun `a quote inside a quoted word stays one argument`() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals(listOf("it's", "a b", "\$HOME", ";"), argsVia(sh = "printf '%s\\n' ${shellQuote("it's")} ${shellQuote("a b")} ${shellQuote("\$HOME")} ${shellQuote(";")}"))
    }

    /** The commands through a real `sh -c`, as sshd runs them, against a `tmux` that prints its arguments. */
    @Test
    fun `sh hands tmux exactly the intended arguments`() {
        assertEquals(
            listOf("-u", "new-session", "-A", "-D", "-s", name, ";", "set-option", "-t", "=$name:", "status", "off", ";", "set-option", "-t", "=$name:", "mouse", "on"),
            argsVia(tmuxAttachCommand(name)),
        )
        assertEquals(listOf("kill-session", "-t", "=$name"), argsVia(tmuxKillCommand(name)))
        assertEquals(listOf("if-shell", "-F", "-t", "=$name:", "#{pane_in_mode}", "send-keys -t =$name: -X cancel"), argsVia(tmuxLeaveModeCommand(name)))
    }

    @Test
    fun `the probe tells absent, fresh and existing apart through sh`() {
        assertEquals(TmuxProbe.ABSENT, parseTmuxProbe(run(tmuxProbeCommand(name), tmux = null)))
        assertEquals(TmuxProbe.FRESH, parseTmuxProbe(run(tmuxProbeCommand(name), tmux = "exit 1")))
        assertEquals(TmuxProbe.EXISTING, parseTmuxProbe(run(tmuxProbeCommand(name), tmux = "exit 0")))
    }

    @Test
    fun `the probe ignores anything but its own markers`() {
        assertEquals(TmuxProbe.ABSENT, parseTmuxProbe(""))
        assertEquals(TmuxProbe.ABSENT, parseTmuxProbe("Welcome to Wiren Board\n/usr/bin/tmux\n"))
        assertEquals(TmuxProbe.FRESH, parseTmuxProbe("motd\r\nfrpjump-tmux\r\n"))
        assertEquals(TmuxProbe.EXISTING, parseTmuxProbe("frpjump-tmux\nfrpjump-attach\n"))
    }

    @Test
    fun `tmux or a plain shell, and what the owner is told`() {
        assertEquals(Resumed.FIRST, resumed(dropped = false, TmuxProbe.ABSENT))
        assertEquals(Resumed.FIRST, resumed(dropped = false, TmuxProbe.FRESH))
        assertEquals(Resumed.REATTACHED, resumed(dropped = false, TmuxProbe.EXISTING))
        assertEquals(Resumed.NEW_SHELL, resumed(dropped = true, TmuxProbe.ABSENT))
        // tmux there, the session gone (the device rebooted): a new shell, said plainly.
        assertEquals(Resumed.NEW_SHELL, resumed(dropped = true, TmuxProbe.FRESH))
        assertEquals(Resumed.REATTACHED, resumed(dropped = true, TmuxProbe.EXISTING))
    }

    @Test
    fun `only the owner's committed close of an open tmux tab kills its session`() {
        assertEquals(name, tmuxToKill(endTmux = true, wasOpen = true, inUse = true, name = name))
        // A tunnel closed, Forget profile, Reconnect replacing the tab.
        assertNull(tmuxToKill(endTmux = false, wasOpen = true, inUse = true, name = name))
        // The connection is down: the tab closes, the session stays.
        assertNull(tmuxToKill(endTmux = true, wasOpen = false, inUse = true, name = name))
        // A plain shell: nothing of tmux to end.
        assertNull(tmuxToKill(endTmux = true, wasOpen = true, inUse = false, name = name))
        assertNull(tmuxToKill(endTmux = true, wasOpen = true, inUse = true, name = null))
    }

    @Test
    fun `tmux failing at once falls back to a plain shell, nothing else does`() {
        assertTrue(tmuxFailedToStart(inUse = true, exitStatus = 1, ranMs = 200))
        assertTrue(tmuxFailedToStart(inUse = true, exitStatus = 127, ranMs = TMUX_START_WINDOW_MS - 1))
        // Ran a while: the session's own end, told as usual.
        assertFalse(tmuxFailedToStart(inUse = true, exitStatus = 1, ranMs = TMUX_START_WINDOW_MS))
        // Detached or the session ended: a normal end.
        assertFalse(tmuxFailedToStart(inUse = true, exitStatus = 0, ranMs = 200))
        // A drop: reconnect, not fall back.
        assertFalse(tmuxFailedToStart(inUse = true, exitStatus = null, ranMs = 200))
        // A plain shell (or tmux already given up in this tab).
        assertFalse(tmuxFailedToStart(inUse = false, exitStatus = 1, ranMs = 200))
    }

    @Test
    fun `the probe output is read up to a bound`() {
        assertEquals("frpjump-tmux\n", readUpTo("frpjump-tmux\n".byteInputStream(), 4096))
        assertEquals("abcd", readUpTo("abcdefgh".byteInputStream(), 4))
        assertEquals("", readUpTo(ByteArray(0).inputStream(), 4096))
    }

    /** The arguments a fake `tmux` gets when [command] runs under `sh -c`. */
    private fun argsVia(command: String? = null, sh: String? = null): List<String> =
        run(sh ?: command!!, tmux = "printf '%s\\n' \"\$@\"").lines().dropLast(1)

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
