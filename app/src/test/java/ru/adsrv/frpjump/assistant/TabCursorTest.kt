package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.ssh.ExecLease
import ru.adsrv.frpjump.ssh.SshState

/**
 * The one source of a tab's output and its cursor ([TabText]) with the tools that read it (`terminal_read`) and the waits that watch it
 * ([AppWaitSources]): a read and an event see the same text. Every behaviour is tried on a tmux tab (the device answers the read commands, here a
 * [FakeTerminal]) and on a tab of the app's buffer.
 */
class TabCursorTest {
    private val tmuxName = "frpjump-0a1b2c-1"
    private val sources = listOf<String?>(null, tmuxName)
    private fun rows(range: IntRange, prefix: String = "row") = range.joinToString("\n") { "$prefix $it" }

    private class Tab(override val id: Int, override val tmuxName: String?, screenRows: Int = 24, limit: Int = 2000) : TabHandle {
        val term = FakeTerminal(screenRows, limit, tmux = tmuxName != null)
        override val profileId = "p1"; override val device = "pi"; override val port = 22; override val user = "root"; override val number = 1
        override val state: SshState = SshState.Open
        @Volatile override var lastOutputNanos = 0L
        @Volatile var missing = false
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var execs = 0
        override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? { execs++; commands += command; return term.exec(command) }
        override fun bufferSource(mode: ReadMode) = term.source(mode)
        /** Prints [text] and tells the tab that something was printed. */
        fun print(vararg text: String) { term.print(*text); lastOutputNanos++ }
    }

    private fun owner(vararg tabs: Tab) = AssistantTerminalTabs(
        tabs = { tabs.filter { !it.missing } }, hidden = { emptySet() }, profileName = { "Home" }, connectionName = { _, _, _ -> null },
        io = Dispatchers.IO, main = Dispatchers.Unconfined, readTimeoutMs = 2_000,
    )

    private fun read(owner: AssistantTerminalTabs, vararg args: Pair<String, Any>) = runBlocking {
        AssistantTerminalReadTool(owner).execute(buildJsonObject { put("tab_id", 1); args.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v as String) } })
    }

    private fun cursorOf(out: String) = out.lines().first().substringAfter("cursor: ").substringBefore("\n")
    private fun body(out: String) = out.lines().drop(1).joinToString("\n")
    private val gone = AssistantTerminalReadTool.CURSOR_GONE

    // ---- the cursor ----

    @Test fun `a cursor is spelled out and read back, and anything else is refused`() {
        val c = FakeTerminal().set(rows(1..5)).cursor()
        assertEquals(c, TabCursor.parse(c.toString()))
        assertTrue(c.toString(), Regex("4\\.0\\.[0-9a-f]{8}").matches(c.toString()))
        assertTrue(TabCursor(999_999_999, 999_999_999, -1).toString().length <= TabCursor.MAX_CHARS)
        for (bad in listOf("", "5", "5.", "5.0", "5.0.", ".0.00000000", "5.0.0000000", "5.0.000000000", "5.0.0000000G", "-5.0.00000000", "1234567890.0.00000000", " 5.0.00000000", "5.0.00000000\n"))
            assertNull(bad, TabCursor.parse(bad))
    }

    // ---- reading from a cursor ----

    @Test fun `a read from a cursor gives the rows from the cursor's row on, and every read hands out the next cursor`() {
        for (name in sources) {
            val tab = Tab(1, name).also { it.term.set(rows(1..6)) }
            val o = owner(tab)
            val first = read(o).text
            assertEquals("$name", rows(1..6), body(first))
            // The terminal's cursor is on row 6: that row is the one still written, so it is part of what follows the cursor.
            tab.term.set(rows(1..9))
            val second = read(o, "from" to cursorOf(first))
            assertEquals("$name", rows(6..9), body(second.text))
            assertEquals(ToolOutcome.SUCCESS, second.outcome)
            tab.term.set(rows(1..9))
            val third = read(o, "from" to cursorOf(second.text))
            assertEquals("$name", "row 9", body(third.text))
            tab.term.set(rows(1..11))
            assertEquals("$name", rows(9..11), body(read(o, "from" to cursorOf(third.text)).text))
        }
    }

    @Test fun `a progress bar on the cursor's row is seen when it changes in place, and a line finished later is not missed`() {
        for (name in sources) {
            val tab = Tab(1, name).also { it.term.set("Reading package lists\nProgress: [ 10%]") }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            tab.term.set("Reading package lists\nProgress: [100%]")
            assertEquals("$name", "Progress: [100%]", body(read(o, "from" to from).text))
            // A half-printed line at the cursor, finished after the cursor was taken.
            tab.term.set("Reading package lists\nDownloading build do")
            val half = cursorOf(read(o).text)
            tab.term.set("Reading package lists\nDownloading build done\n\$ ")
            assertEquals("$name", "Downloading build done\n\$", body(read(o, "from" to half).text))
        }
    }

    @Test fun `when no rows can have been dropped the row number places the cursor, whatever the rows say`() {
        for (name in sources) {
            // A "not ready" loop on a small history: the same row over and over, but history is far from its limit.
            val tab = Tab(1, name).also { t -> t.term.set((1..60).joinToString("\n") { "not ready" }) }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            tab.term.print(*Array(5) { "not ready" }, "ready")
            assertEquals("$name", (List(5) { "not ready" } + "ready").let { listOf("not ready") + it }.joinToString("\n"), body(read(o, "from" to from).text))
        }
    }

    // ---- when rows can have been dropped ----

    @Test fun `repeated identical rows on a full history are not guessed`() {
        for (name in sources) {
            val tab = Tab(1, name, screenRows = 5, limit = 100).also { t -> repeat(150) { t.term.print("not ready") } }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            repeat(30) { tab.term.print("not ready") }
            tab.term.print("ready")
            val out = read(o, "from" to from, "lines" to 3)
            assertEquals("$name", gone, out.text.lines()[1])
            assertEquals("$name", (List(7) { "not ready" } + "ready").joinToString("\n"), out.text.lines().drop(2).joinToString("\n"))
        }
    }

    @Test fun `a redrawn screen loses nothing and a cleared history is told`() {
        for (name in sources) {
            val tab = Tab(1, name, screenRows = 5).also { it.term.set(rows(1..40)) }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            // Ctrl-L: the screen's rows scroll into history, the prompt is drawn at the top. Nothing is lost.
            tab.term.print("", "", "", "", "\$ ")
            assertEquals("$name", "row 40\n\n\n\n\n\$", body(read(o, "from" to from).text))
            // CSI 3J / clear: the history goes with the screen. The rows are no longer kept.
            tab.term.clear().print("\$ ")
            val cleared = read(o, "from" to from)
            assertEquals("$name", gone, cleared.text.lines()[1])
            assertEquals("$name", "\$", cleared.text.lines().drop(2).joinToString("\n"))
        }
    }

    @Test fun `a drop of 200 rows is found again by the checksum when it is unique, and said when the rows went`() {
        for (name in sources) {
            val tab = Tab(1, name, screenRows = 24, limit = 2000).also { it.term.set(rows(1..1900)) }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            // 300 more rows: tmux drops 200 at once (the app one by one), the cursor's rows sit about 200 higher up.
            tab.term.print(*(1901..2200).map { "row $it" }.toTypedArray())
            val out = read(o, "from" to from)
            assertEquals("$name", rows(1900..2200), body(out.text))
            assertFalse("$name", out.text.contains(gone))
            // Dropped past the cursor's own rows.
            tab.term.print(*(2201..4600).map { "row $it" }.toTypedArray())
            val lost = read(o, "from" to from, "lines" to 2)
            assertEquals("$name", gone, lost.text.lines()[1])
        }
    }

    // ---- what a read costs ----

    @Test fun `a read from a cursor over a long history fetches the rows from the cursor on, not the history`() {
        val tab = Tab(1, tmuxName).also { it.term.set(rows(1..1500)) }
        val o = owner(tab)
        val from = cursorOf(read(o).text)
        tab.term.print("row 1501", "row 1502")
        val before = tab.commands.size
        assertEquals(rows(1500..1502), body(read(o, "from" to from).text))
        val command = tab.commands[before]
        assertEquals(ru.adsrv.frpjump.ssh.tmuxReadFromCommand(tmuxName, TabCursor.parse(from)!!.position, TabCursor.parse(from)!!.history), command)
        // What the device sent: a few rows, the rows above the cursor and the numbers.
        assertTrue(tab.term.exec(command)!!.lines().size < 12)
        // A read without a cursor asks for the history it was asked for, no more.
        val count = tab.commands.size
        read(o, "lines" to 7)
        assertEquals(ru.adsrv.frpjump.ssh.tmuxReadScreenCommand(tmuxName, 7), tab.commands[count])
    }

    // ---- a real terminal buffer: its history limit is the «История терминала» setting ----

    private val output = object : com.termux.terminal.TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun emulator() = com.termux.terminal.TerminalEmulator(output, 20, 4, 12, 24, 100, null)
    private fun com.termux.terminal.TerminalEmulator.feed(text: String) { val b = text.toByteArray(); append(b, b.size) }

    @Test fun `a real buffer is read as physical rows from the cursor row, and says so when the oldest rows were dropped`() {
        val e = emulator()
        e.feed((1..10).joinToString("\r\n") { "line $it" })
        val cursor = TabText(terminalBufferSource(e, ReadMode.Screen(0))).cursor
        e.feed("\r\n" + (11..15).joinToString("\r\n") { "line $it" })
        // Below its capacity the buffer cannot have dropped rows: the row number places the cursor, and only the rows from it are copied.
        val from = TabText(terminalBufferSource(e, ReadMode.From(cursor)))
        assertEquals((10..15).joinToString("\n") { "line $it" }, from.since(cursor))
        // A long wrapped line is physical rows here.
        val e2 = emulator()
        e2.feed("0123456789abcdefghij0123456789")
        val start = TabText(terminalBufferSource(emulator(), ReadMode.Screen(0))).cursor
        assertEquals("0123456789abcdefghij\n0123456789", TabText(terminalBufferSource(e2, ReadMode.From(start))).since(start))
        // 100 rows of history: 300 more lines push everything the cursor stood on out.
        e.feed("\r\n" + (16..315).joinToString("\r\n") { "line $it" })
        assertNull(TabText(terminalBufferSource(e, ReadMode.From(cursor))).since(cursor))
        assertEquals("line 315", TabText(terminalBufferSource(e, ReadMode.Screen(0))).screen().lines().last())
    }

    // ---- waits ----

    private val clock = java.util.concurrent.atomic.AtomicLong(0)
    private fun waitSources(o: AssistantTerminalTabs) = AppWaitSources(o, null, pollMs = 5, nanos = { clock.get() }, rereadMs = 0, settleMs = 0)
    private fun spec(kind: WaitKind, text: String? = null, from: String? = null, seconds: Int? = null) = WaitSpec(kind, 5, tabId = 1, text = text, from = from, seconds = seconds)
    private fun await(s: AppWaitSources, spec: WaitSpec, ms: Long = 400): SourceResult? = runBlocking { withTimeoutOrNull(ms) { s.await(spec) } }
    private fun secs(n: Number) = (n.toDouble() * 1_000_000_000L).toLong()

    @Test fun `tab_text from a cursor sees text printed before the wait was set`() {
        for (name in sources) {
            val tab = Tab(1, name).also { it.term.set("\$ make") }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            // The text is printed, and only then is the wait set (the model was busy): the cursor says where to count from.
            tab.print("compiling", "build done", "\$ ")
            assertEquals("$name", SourceResult.FIRED, await(waitSources(o), spec(WaitKind.TAB_TEXT, "build done", from)))
        }
    }

    @Test fun `tab_text without a cursor starts at the current end, so old history never fires it`() {
        for (name in sources) {
            val tab = Tab(1, name).also { it.term.set("build done\nbuild done\n\$ ") }
            val o = owner(tab)
            assertNull("$name", await(waitSources(o), spec(WaitKind.TAB_TEXT, "build done"), ms = 200))
            // Output that comes after the start does.
            val s = waitSources(o)
            val result = runBlocking {
                val waiting = async(Dispatchers.Default) { withTimeoutOrNull(3_000) { s.await(spec(WaitKind.TAB_TEXT, "build done")) } }
                delay(100)
                tab.print("build done", "\$ ")
                waiting.await()
            }
            assertEquals("$name", SourceResult.FIRED, result)
        }
    }

    @Test fun `tab_text finishes a line that was half printed when the wait started`() {
        for (name in sources) {
            val tab = Tab(1, name).also { it.term.set("Downloading build do") }
            val o = owner(tab)
            val s = waitSources(o)
            val result = runBlocking {
                val waiting = async(Dispatchers.Default) { withTimeoutOrNull(3_000) { s.await(spec(WaitKind.TAB_TEXT, "build done")) } }
                delay(100)
                tab.term.set("Downloading build done"); tab.lastOutputNanos++
                waiting.await()
            }
            assertEquals("$name", SourceResult.FIRED, result)
        }
    }

    @Test fun `tab_text matches across the break of a wrapped line, and looks at the screen when the cursor's rows are gone`() {
        for (name in sources) {
            val tab = Tab(1, name).also { it.term.set("\$ ") }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            tab.print("see the build", "done now", "\$ ")
            assertEquals("$name", SourceResult.FIRED, await(waitSources(o), spec(WaitKind.TAB_TEXT, "build done now", from)))
        }
        for (name in sources) {
            val tab = Tab(1, name, screenRows = 5, limit = 100).also { t -> repeat(150) { t.term.print("not ready") } }
            val o = owner(tab)
            val from = cursorOf(read(o).text)
            repeat(30) { tab.term.print("not ready") }
            tab.print("all done")
            // The rows from the cursor cannot be told: what a read without a cursor shows is looked at.
            assertEquals("$name", SourceResult.FIRED, await(waitSources(o), spec(WaitKind.TAB_TEXT, "all done", from)))
        }
    }

    @Test fun `a wait moves its read point forward on each look, and waits on one tab share a read`() {
        val tab = Tab(1, tmuxName).also { it.term.set(rows(1..800)) }
        val o = owner(tab)
        val s = waitSources(o)
        val from = cursorOf(read(o).text)
        tab.print("row 801")
        val one = spec(WaitKind.TAB_TEXT, "never", from)
        val result = runBlocking {
            val a = async(Dispatchers.Default) { withTimeoutOrNull(600) { s.await(one) } }
            val b = async(Dispatchers.Default) { withTimeoutOrNull(600) { s.await(one) } }
            delay(150)
            tab.print("row 802", "row 803")
            delay(150)
            a.await() to b.await()
        }
        assertEquals(null to null, result)
        val reads = tab.commands.drop(1).map { Regex(" sh '[^']*' (\\d+) (\\d+)$").find(it)!!.groupValues[1].toInt() }
        // The two waits asked for the same read of the same tab once; the next read starts from where the last one ended, never from the wait's cursor again.
        assertEquals(reads.sorted(), reads)
        assertTrue(reads.toString(), reads.first() >= TabCursor.parse(from)!!.position && reads.last() > reads.first())
        assertEquals("one read per look, shared by both waits", reads.distinct(), reads)
    }

    @Test fun `a wait on a tab that is gone ends`() {
        val tab = Tab(1, null).also { it.term.set("x"); it.missing = true }
        val o = owner(tab)
        assertEquals(SourceResult.GONE, await(waitSources(o), spec(WaitKind.TAB_TEXT, "x")))
        assertEquals(SourceResult.GONE, await(waitSources(o), spec(WaitKind.TAB_QUIET)))
    }

    @Test fun `tab_quiet takes seconds of silence, ten by default`() {
        val tab = Tab(1, null).also { it.term.set("\$ ") }
        val s = waitSources(owner(tab))
        runBlocking {
            clock.set(0); tab.lastOutputNanos = 0
            val byDefault = async(Dispatchers.Default) { s.await(spec(WaitKind.TAB_QUIET)) }
            delay(50)
            clock.set(secs(9.5)); delay(60)
            assertFalse("9.5 s is not 10", byDefault.isCompleted)
            // Output at 9.5 s starts the silence again.
            tab.lastOutputNanos = secs(9.5); clock.set(secs(19.0)); delay(60)
            assertFalse("only 9.5 s since the output", byDefault.isCompleted)
            clock.set(secs(19.6)); delay(60)
            assertEquals(SourceResult.FIRED, withTimeoutOrNull(1_000) { byDefault.await() })
            // Its own seconds, counted from the start of the wait when the last output was before it.
            val three = async(Dispatchers.Default) { s.await(spec(WaitKind.TAB_QUIET, seconds = 3)) }
            delay(50)
            clock.set(secs(22.0)); delay(60)
            assertFalse("2.4 s since the wait began", three.isCompleted)
            clock.set(secs(22.7)); delay(60)
            assertEquals(SourceResult.FIRED, withTimeoutOrNull(1_000) { three.await() })
        }
    }

    @Test fun `tab_quiet from a cursor is quiet at once when what followed the cursor was printed long ago`() {
        for (name in sources) {
            val tab = Tab(1, name).also { it.term.set("\$ ls") }
            val o = owner(tab)
            val s = waitSources(o)
            val from = cursorOf(read(o).text)
            // The command printed its output at 1 s and nothing since; the wait is set at 60 s.
            tab.term.print("file a", "file b", "\$ "); tab.lastOutputNanos = secs(1); clock.set(secs(60))
            assertEquals("$name", SourceResult.FIRED, await(s, spec(WaitKind.TAB_QUIET, from = from, seconds = 5)))
            // Without the cursor the silence counts from the start of the wait: not quiet yet at its start.
            assertNull("$name", await(s, spec(WaitKind.TAB_QUIET, seconds = 5), ms = 100))
            // With a cursor but nothing printed after it (only the prompt row), also from the start of the wait.
            val none = cursorOf(read(o).text)
            assertNull("$name", await(s, spec(WaitKind.TAB_QUIET, from = none, seconds = 5), ms = 100))
        }
    }
}
