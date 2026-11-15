package ru.adsrv.frpjump.ssh

import com.termux.terminal.RemoteTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.adsrv.frpjump.assistant.AssistantTerminalTabs
import ru.adsrv.frpjump.assistant.InputPin
import ru.adsrv.frpjump.assistant.TabHandle
import ru.adsrv.frpjump.assistant.TypedInput
import java.io.File

/**
 * The assistant's tmux reads, as they are sent (the command line, through `sh -c`), against a stand-in `tmux` (a shell and awk script) that keeps a
 * terminal in a file and behaves as tmux does where it matters here: one invocation runs its `;`-chained commands on one snapshot of the terminal,
 * output that comes between two invocations (GROW rows each time, as on a tab that prints all the time) is seen by the next one, and a full history
 * (LIMIT) loses a tenth of its rows at once. Every invocation is logged.
 */
class TmuxReadScriptTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "frpjump-0a1b2c-1"

    private val stub = """
        #!/bin/sh
        D="${'$'}STUB"; PH=6; L=${'$'}{LIMIT:-2000}
        n=${'$'}(wc -l < "${'$'}D/lines" | tr -d ' ')
        g=${'$'}{GROW:-0}
        while [ "${'$'}g" -gt 0 ]; do n=${'$'}((n+1)); echo "out ${'$'}n" >> "${'$'}D/lines"; g=${'$'}((g-1)); done
        if [ "${'$'}n" -gt "${'$'}PH" ]; then H=${'$'}((n-PH)); else H=0; fi
        if [ "${'$'}H" -ge "${'$'}L" ]; then t=${'$'}((L/10)); tail -n +${'$'}((t+1)) "${'$'}D/lines" > "${'$'}D/tmp" && mv "${'$'}D/tmp" "${'$'}D/lines"; n=${'$'}((n-t)); if [ "${'$'}n" -gt "${'$'}PH" ]; then H=${'$'}((n-PH)); else H=0; fi; fi
        echo "${'$'}*" >> "${'$'}D/log"
        CY=${'$'}((n-1-H)); [ "${'$'}CY" -lt 0 ] && CY=0
        run() {
          case "${'$'}cmd" in
          capture-pane)
            case "${'$'}S" in -) st=0;; -*) k=${'$'}{S#-}; st=${'$'}((H-k)); [ "${'$'}st" -lt 0 ] && st=0;; *) st=${'$'}((H+S));; esac
            awk -v s="${'$'}st" -v t="${'$'}((H+PH))" '{a[NR-1]=${'$'}0} END{for(r=s;r<t;r++) print (r in a) ? a[r] : ""}' "${'$'}D/lines" ;;
          display-message) echo "${'$'}H ${'$'}L ${'$'}CY ${'$'}PH 0" ;;
          esac
        }
        cmd=""; NEXT=""; S=""
        for a in "${'$'}@" ";"; do
          if [ "${'$'}a" = ";" ]; then run; cmd=""; NEXT=""; S=""; continue; fi
          if [ -z "${'$'}cmd" ]; then cmd=${'$'}a; continue; fi
          case "${'$'}NEXT" in S) S=${'$'}a; NEXT=""; continue;; V) NEXT=""; continue;; esac
          case "${'$'}a" in -S) NEXT=S;; -t) NEXT=V;; esac
        done
    """.trimIndent() + "\n"

    /** A terminal of [rows] rows ("row 1" ...) in a stand-in tmux dir; [grow] rows come in between invocations, [limit] is the history limit. */
    private inner class Terminal(rows: Int, val grow: Int = 0, val limit: Int = 2000) {
        val dir: File = tmp.newFolder()
        init {
            File(dir, "lines").writeText((1..rows).joinToString("") { "row $it\n" })
            File(dir, "tmux").apply { writeText(stub); setExecutable(true) }
        }
        val log get() = File(dir, "log").takeIf { it.exists() }?.readLines().orEmpty()
        fun append(vararg more: String) = File(dir, "lines").appendText(more.joinToString("") { it + "\n" })

        /** What the command prints when run as it is sent. */
        fun run(command: String): String {
            val builder = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true)
            builder.environment().apply { put("PATH", dir.path + ":" + System.getenv("PATH")); put("STUB", dir.path); put("GROW", "$grow"); put("LIMIT", "$limit") }
            val p = builder.start()
            val text = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw java.io.IOException("exit")
            return text
        }

        fun tab(): StubTab = StubTab(this)
    }

    private class Pin : InputPin

    private class StubTab(val terminal: Terminal) : TabHandle {
        override val id = 1; override val profileId = "p1"; override val device = "pi"; override val port = 22; override val user = "root"; override val number = 1
        override val state: SshState = SshState.Open
        override val tmuxName: String = "frpjump-0a1b2c-1"
        val typed = ArrayList<ByteArray>()
        private val pin = Pin()
        override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? = terminal.run(command)
        override fun bufferSource(mode: ru.adsrv.frpjump.assistant.ReadMode) = throw IllegalStateException("a tmux tab")
        override fun capturePin(): InputPin? = pin
        override fun pinCurrent(pin: InputPin) = pin === this.pin
        override fun type(bytes: ByteArray, pin: InputPin): RemoteTerminal.TypeOutcome { typed += bytes; return RemoteTerminal.TypeOutcome.QUEUED }
    }

    private fun owner(tab: TabHandle) = AssistantTerminalTabs(
        tabs = { listOf(tab) }, hidden = { emptySet() }, profileName = { "Home" }, connectionName = { _, _, _ -> null },
        connectionId = { _, _, _ -> "c1" }, io = Dispatchers.IO, main = Dispatchers.Unconfined, readTimeoutMs = 10_000,
    )

    private fun available() = assumeTrue(File("/bin/sh").exists() && System.getenv("PATH").split(':').any { File(it, "awk").exists() })

    @Test fun `one invocation answers all its commands in order, on one snapshot`() {
        available()
        val t = Terminal(30, grow = 3)
        // The terminal had 33 rows when this invocation saw it (3 came in first): history 27, a screen of 6. In order: the 8 rows to show (2 of
        // history and the screen), then the 9 physical rows from three above the screen, then the numbers.
        val screen = t.run(tmuxReadScreenCommand(name, 2)).trimEnd('\n').split('\n')
        assertEquals("27 2000 5 6 0", screen.last())
        assertEquals(8 + 9 + 1, screen.size)
        assertEquals(listOf("row 26", "row 27"), screen.take(2))
        assertEquals("out 33", screen[16])
        assertEquals(1, t.log.size)
        // A window and the whole history: the rows, then the numbers, again one invocation each, each on the rows it found (3 more came in first).
        val window = t.run(tmuxReadWindowCommand(name, 10)).trimEnd('\n').split('\n')
        assertEquals("30 2000 5 6 0", window.last())
        assertEquals(10 + 6 + 1, window.size)
        val all = t.run(tmuxReadWindowCommand(name, null)).trimEnd('\n').split('\n')
        assertEquals("33 2000 5 6 0", all.last())
        assertEquals(39 + 1, all.size)
        assertEquals(3, t.log.size)
    }

    @Test fun `a tab that prints all the time still gives typing and a screen and rows from a cursor`() {
        available()
        val t = Terminal(500, grow = 4)
        val tab = t.tab()
        val tabs = owner(tab)
        runBlocking {
            var cursor = tabs.type(TypedInput(1, "ls\n", emptyList()), tab.capturePin())
            repeat(6) {
                // Each call sees four rows more than the one before, and none of them ever fails or says "no longer kept".
                val screen = tabs.read(1, 5)
                assertTrue(screen.text, screen.text.isNotEmpty())
                val next = tabs.type(TypedInput(1, "x", emptyList()), tab.capturePin())
                val rows = tabs.read(1, 5, cursor)
                assertFalse("no longer kept at $it", rows.cursorGone)
                assertTrue(rows.text, rows.text.lines().first().startsWith("out ") || rows.text.lines().first().startsWith("row "))
                cursor = next
            }
        }
        assertEquals(7, tab.typed.size)
        // Every read and every typing was ONE tmux invocation: 1 + 6 x 3 of them, each with its commands chained.
        assertEquals(19, t.log.size)
        assertTrue(t.log.all { it.contains(" ; ") })
    }

    @Test fun `a cursor older than the window goes to the whole history, in one invocation more`() {
        available()
        val t = Terminal(300)
        val tab = t.tab()
        val tabs = owner(tab)
        runBlocking {
            val cursor = tabs.read(1, 5).cursor
            // 600 rows more: the window (a tenth of the limit and the screen and three: 209 rows) no longer reaches the cursor.
            t.append(*(1..600).map { "later $it" }.toTypedArray())
            val before = t.log.size
            val rows = tabs.read(1, 5, cursor)
            assertFalse(rows.cursorGone)
            assertEquals("row 300", rows.text.lines().first())
            assertEquals("later 600", rows.text.lines().last())
            val used = t.log.drop(before)
            assertEquals(used.toString(), 2, used.size)
            assertTrue(used[0], used[0].contains("capture-pane -p -S -209 "))
            assertTrue(used[1], used[1].contains("capture-pane -p -S - "))
        }
    }

    @Test fun `one trim of a full history is found inside the window, in one invocation`() {
        available()
        val t = Terminal(104, limit = 100)
        val tab = t.tab()
        val tabs = owner(tab)
        runBlocking {
            val cursor = tabs.read(1, 5).cursor
            // Six rows more: the history reaches its limit and the stand-in drops a tenth (10 rows) at once; the cursor's rows sit 10 higher.
            t.append(*(1..6).map { "later $it" }.toTypedArray())
            val before = t.log.size
            val rows = tabs.read(1, 5, cursor)
            assertFalse(rows.cursorGone)
            assertEquals(listOf("row 104", "later 1", "later 2", "later 3", "later 4", "later 5", "later 6"), rows.text.lines())
            assertEquals(1, t.log.size - before)
        }
    }
}
