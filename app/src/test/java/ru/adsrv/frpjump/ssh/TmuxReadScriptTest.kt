package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The scripts of the assistant's tmux reads, run as they are sent (through `sh -c`) against a stand-in `tmux` that answers the two questions about
 * numbers from files and records every capture: what they capture, from which row, and what they print.
 */
class TmuxReadScriptTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "frpjump-0a1b2c-7"

    private class Run(val out: List<String>, val captures: List<String>)

    /** Runs [command] with a tmux that says [start] to the first question of numbers and [end] (with the copy mode flag) to the last. */
    private fun run(command: String, start: String, end: String): Run {
        assumeTrue(File("/bin/sh").exists())
        val dir = tmp.newFolder()
        File(dir, "start").writeText("$start\n"); File(dir, "end").writeText("$end\n"); File(dir, "rows").writeText("r1\nr2\n")
        File(dir, "tmux").apply {
            writeText("""
                #!/bin/sh
                case "${'$'}1" in
                display-message) for last; do :; done
                  case "${'$'}last" in *pane_in_mode*) cat "${'$'}STUB/end";; *) cat "${'$'}STUB/start";; esac ;;
                capture-pane) echo "${'$'}*" >> "${'$'}STUB/log"; cat "${'$'}STUB/rows" ;;
                esac
            """.trimIndent() + "\n")
            setExecutable(true)
        }
        val builder = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true)
        builder.environment()["PATH"] = dir.path + ":" + System.getenv("PATH"); builder.environment()["STUB"] = dir.path
        val text = builder.start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
        val log = File(dir, "log").takeIf { it.exists() }?.readLines().orEmpty()
        return Run(text.trimEnd('\n').split('\n'), log)
    }

    @Test fun `a read of an empty history asks for no rows above the cursor`() {
        val r = run(tmuxReadScreenCommand(name, 5), "0 2000 0 24", "0 2000 0 24 0")
        assertEquals(listOf("capture-pane -p -J -S -5 -t =$name:"), r.captures)
        assertEquals(listOf("r1", "r2", "0 2000 0 24", "0 2000 0 24 0"), r.out)
    }

    @Test fun `the rows above the cursor are asked for relative to the screen, and the numbers come twice`() {
        val r = run(tmuxReadScreenCommand(name, 0), "5 2000 2 24", "5 2000 2 24 1")
        assertEquals(listOf("capture-pane -p -J -S -0 -t =$name:", "capture-pane -p -S -1 -E 1 -t =$name:"), r.captures)
        // Output that came in meanwhile shows as two different lines of numbers; the reader compares them.
        val drift = run(tmuxReadScreenCommand(name, 0), "10 2000 3 24", "12 2000 3 24 0")
        assertEquals(listOf("10 2000 3 24", "12 2000 3 24 0"), drift.out.takeLast(2))
    }

    @Test fun `a read from a cursor starts three rows above it when no rows can have dropped`() {
        // 50 rows of history, far from its limit and not fewer than at the cursor (45): the row number places it. Row 37 is S = 37 - 50 = -13.
        assertEquals("capture-pane -p -S -13 -t =$name:", run(tmuxReadFromCommand(name, 40, 45), "50 2000 5 24", "50 2000 5 24 0").captures.first())
        // Near the top: never before the first row.
        assertEquals("capture-pane -p -S -10 -t =$name:", run(tmuxReadFromCommand(name, 2, 0), "10 2000 5 24", "10 2000 5 24 0").captures.first())
    }

    @Test fun `a read from a cursor starts one trim higher when rows may have dropped, and the whole history on request`() {
        // 1900 of 2000: a tenth (200) more above the three rows: 1850 - 3 - 200 = 1647, S = 1647 - 1900 = -253.
        assertEquals("capture-pane -p -S -253 -t =$name:", run(tmuxReadFromCommand(name, 1850, 1890), "1900 2000 5 24", "1900 2000 5 24 0").captures.first())
        // Fewer rows of history than at the cursor (cleared): also not exact.
        assertEquals("capture-pane -p -S -253 -t =$name:", run(tmuxReadFromCommand(name, 1850, 1950), "1900 2000 5 24", "1900 2000 5 24 0").captures.first())
        assertEquals("capture-pane -p -S - -t =$name:", run(tmuxReadFromCommand(name, 1850, 1890, full = true), "1900 2000 5 24", "1900 2000 5 24 0").captures.first())
        val r = run(tmuxReadFromCommand(name, 1850, 1890), "1900 2000 0 24", "1900 2000 0 24 0")
        assertTrue(r.captures.toString(), r.captures.size == 2)
    }
}
