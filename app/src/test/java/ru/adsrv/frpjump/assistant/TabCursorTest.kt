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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.ssh.ExecLease
import ru.adsrv.frpjump.ssh.SshState

/**
 * The one source of a tab's output and its cursor ([TabText]) with the tools that read it (`terminal_read`) and the waits that watch it
 * ([AppWaitSources]): a read and an event see the same text.
 */
class TabCursorTest {
    private fun text(rows: String, screen: Int = 24) = TabText(SourceText(rows, screen))
    private fun rows(range: IntRange, prefix: String = "line") = range.joinToString("\n") { "$prefix $it" }

    // ---- the cursor ----

    @Test fun `a cursor is spelled out and read back, and anything else is refused`() {
        val c = text(rows(1..5)).cursor
        assertEquals(c, TabCursor.parse(c.toString()))
        assertTrue(c.toString(), Regex("5\\.[0-9a-f]{8}").matches(c.toString()))
        assertTrue(c.toString().length <= TabCursor.MAX_CHARS)
        for (bad in listOf("", "5", "5.", ".00000000", "5.0000000", "5.000000000", "5.0000000G", "-5.00000000", "1234567890.00000000", " 5.00000000", "5.00000000\n"))
            assertNull(bad, TabCursor.parse(bad))
    }

    @Test fun `reading from a cursor gives only the lines printed after it`() {
        val before = text(rows(1..10))
        val later = text(rows(1..14))
        assertEquals(rows(11..14), later.since(before.cursor))
        assertEquals("nothing new", "", before.since(before.cursor))
        // Every read hands out the cursor of its own end, so reads chain without gaps or repeats.
        val third = text(rows(1..16))
        assertEquals(rows(15..16), third.since(later.cursor))
        // Blank rows of the screen below the text are not output yet; blank rows between lines are.
        assertEquals(rows(1..3), text(rows(1..3) + "\n\n\n").screen(0))
        assertEquals("line 1\n\n\nline 2", text("line 1\n\n\nline 2\n\n").all())
        assertEquals("\n\nline 2", text("line 1\n\n\nline 2").since(text("line 1").cursor))
    }

    @Test fun `the last line may be typed on without counting as output after the cursor`() {
        val prompt = text("done\n\$ ")
        // The command is typed on the prompt line and run: its echo is the prompt line changing, the output is what follows.
        val after = text("done\n\$ ls\nfile\n\$ ")
        assertEquals("file\n\$", after.since(prompt.cursor))
        // Typed but not run: the prompt line only changed, nothing was printed after the cursor.
        assertEquals("", text("done\n\$ ls").since(prompt.cursor))
    }

    @Test fun `the screen with its history and the cut by lines`() {
        val t = text(rows(1..30), screen = 5)
        assertEquals(rows(26..30), t.screen(0))
        assertEquals(rows(21..30), t.screen(5))
        assertEquals(rows(1..30), t.screen(1000))
        // A screen with blank rows below the text: the history counts from the top of the screen.
        assertEquals(rows(1..2), text(rows(1..2) + "\n\n\n\n", screen = 5).screen(100))
        assertEquals("", text("", 5).screen(10))
    }

    @Test fun `lines dropped from the top are found again by the rows before the cursor, and gone ones are told`() {
        val all = (1..200).map { "row $it" }
        val cursor = text(all.take(100).joinToString("\n")).cursor
        // 40 lines dropped by the history limit and 30 more printed: the cursor's rows now sit 40 higher.
        val now = text(all.subList(40, 130).joinToString("\n"))
        assertEquals(all.subList(100, 130).joinToString("\n"), now.since(cursor))
        // The cursor's own rows were dropped as well.
        assertNull(text(all.subList(150, 200).joinToString("\n")).since(cursor))
        // A cleared history or another tab's cursor is not found either.
        assertNull(text("fresh\nstart").since(cursor))
        assertNull(text(rows(1..100, "other")).since(cursor))
    }

    @Test fun `the same rows give the same cursor and other rows another`() {
        assertEquals(text(rows(1..9)).cursor, text(rows(1..9)).cursor)
        assertNotEquals(text(rows(1..9)).cursor, text(rows(1..9, "other")).cursor)
        assertNotEquals(text(rows(1..9)).cursor, text(rows(1..8)).cursor)
    }

    // ---- a real terminal buffer: its history limit is the «История терминала» setting ----

    @Test fun `a real buffer that dropped the oldest rows says so, and one that did not gives the new rows`() {
        val out = object : com.termux.terminal.TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        val e = com.termux.terminal.TerminalEmulator(out, 20, 4, 12, 24, 100, null)
        fun feed(text: String) { val b = text.toByteArray(); e.append(b, b.size) }
        feed((1..10).joinToString("\r\n") { "line $it" })
        val cursor = TabText(terminalBufferSource(e)).cursor
        feed("\r\n" + (11..15).joinToString("\r\n") { "line $it" })
        assertEquals((11..15).joinToString("\n") { "line $it" }, TabText(terminalBufferSource(e)).since(cursor))
        // 100 history rows: 300 more lines push everything the cursor stood on out.
        feed("\r\n" + (16..315).joinToString("\r\n") { "line $it" })
        assertNull(TabText(terminalBufferSource(e)).since(cursor))
        assertEquals("line 315", TabText(terminalBufferSource(e)).screen(0).lines().last())
    }

    // ---- terminal_read ----

    private class Tab(
        override val id: Int, override val tmuxName: String? = null, var rows: String = "", var height: Int = 24,
    ) : TabHandle {
        override val profileId = "p1"; override val device = "pi"; override val port = 22; override val user = "root"; override val number = 1
        override val state: SshState = SshState.Open
        @Volatile override var lastOutputNanos = 0L
        @Volatile var execs = 0
        @Volatile var missing = false
        override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? { execs++; return rows + "\n" + height + "\n" }
        override fun bufferSource() = SourceText(rows, height)
    }

    private fun owner(vararg tabs: Tab) = AssistantTerminalTabs(
        tabs = { tabs.filter { !it.missing } }, hidden = { emptySet() }, profileName = { "Home" }, connectionName = { _, _, _ -> null },
        io = Dispatchers.IO, main = Dispatchers.Unconfined, readTimeoutMs = 2_000,
    )

    private fun read(owner: AssistantTerminalTabs, vararg args: Pair<String, Any>) = runBlocking {
        AssistantTerminalReadTool(owner).execute(buildJsonObject { put("tab_id", 1); args.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v as String) } })
    }

    private fun cursorOf(out: String) = out.lines().first().substringAfter("cursor: ").substringBefore("\n")

    @Test fun `a read from a cursor returns the new lines of either source, and every read hands out the next cursor`() {
        for (name in listOf(null, "frpjump-0a1b2c-1")) {
            val tab = Tab(1, name, rows(1..6))
            val o = owner(tab)
            val first = read(o).text
            assertEquals("$name", rows(1..6), first.lines().drop(1).joinToString("\n"))
            tab.rows = rows(1..9)
            val second = read(o, "from" to cursorOf(first))
            assertEquals(rows(7..9), second.text.lines().drop(1).joinToString("\n"))
            assertEquals(ToolOutcome.SUCCESS, second.outcome)
            // Nothing printed since: the header alone, with the same cursor.
            val third = read(o, "from" to cursorOf(second.text))
            assertEquals(1, third.text.lines().size)
            assertEquals(cursorOf(second.text), cursorOf(third.text))
            tab.rows = rows(1..11)
            assertEquals(rows(10..11), read(o, "from" to cursorOf(third.text)).text.lines().drop(1).joinToString("\n"))
        }
    }

    @Test fun `a cursor whose lines are gone gets the approved reply, then the screen and the remaining history`() {
        for (name in listOf(null, "frpjump-0a1b2c-1")) {
            val tab = Tab(1, name, rows(1..100), height = 5)
            val o = owner(tab)
            val cursor = cursorOf(read(o).text)
            tab.rows = rows(400..450)
            val out = read(o, "from" to cursor, "lines" to 3)
            val lines = out.text.lines()
            assertEquals("$name", "Lines before the cursor are no longer kept; this is the screen and the history that remain.", lines[1])
            assertEquals(rows(443..450), lines.drop(2).joinToString("\n"))
            assertEquals("the reply carries the cursor of now", TabText(SourceText(tab.rows, 5)).cursor.toString(), cursorOf(out.text))
            assertEquals(ToolOutcome.SUCCESS, out.outcome)
        }
    }

    // ---- waits ----

    private val clock = java.util.concurrent.atomic.AtomicLong(0)
    private fun sources(o: AssistantTerminalTabs) = AppWaitSources(o, null, pollMs = 5, nanos = { clock.get() }, rereadMs = 0, settleMs = 0)
    private fun spec(kind: WaitKind, text: String? = null, from: String? = null, seconds: Int? = null) = WaitSpec(kind, 5, tabId = 1, text = text, from = from, seconds = seconds)
    private fun await(s: AppWaitSources, spec: WaitSpec, ms: Long = 400): SourceResult? = runBlocking { withTimeoutOrNull(ms) { s.await(spec) } }
    private fun secs(n: Number) = (n.toDouble() * 1_000_000_000L).toLong()

    @Test fun `tab_text from a cursor sees text printed before the wait was set`() {
        for (name in listOf(null, "frpjump-0a1b2c-1")) {
            val tab = Tab(1, name, "\$ make")
            val o = owner(tab)
            val from = tab.let { read(o).text }.let(::cursorOf)
            // The text is printed, and only then is the wait set (the model was busy): the cursor says where to count from.
            tab.rows = "\$ make\ncompiling\nbuild done\n\$ "
            tab.lastOutputNanos = 7
            assertEquals("$name", SourceResult.FIRED, await(sources(o), spec(WaitKind.TAB_TEXT, "build done", from)))
            // Without a cursor the same text does not count: it came before the wait began.
            assertNull("$name", await(sources(o), spec(WaitKind.TAB_TEXT, "build done"), ms = 150))
        }
    }

    @Test fun `tab_text without a cursor counts what is printed after the wait started, from the same lines a read shows`() {
        val tab = Tab(1, "frpjump-0a1b2c-1", "old line\n\$ ")
        val o = owner(tab)
        val s = sources(o)
        val result = runBlocking {
            val waiting = async(Dispatchers.Default) { withTimeoutOrNull(3_000) { s.await(spec(WaitKind.TAB_TEXT, "ready now")) } }
            delay(100)
            tab.rows = "old line\n\$ \nready now\n\$ "; tab.lastOutputNanos = 5
            waiting.await()
        }
        assertEquals(SourceResult.FIRED, result)
        // A read sees that same text.
        assertTrue(read(o).text.contains("ready now"))
    }

    @Test fun `tab_text looks in everything still kept when the cursor's lines are gone, and in no other tab`() {
        val tab = Tab(1, null, rows(1..100))
        val o = owner(tab)
        val from = cursorOf(read(o).text)
        tab.rows = rows(400..450) + "\nall done"; tab.lastOutputNanos = 3
        assertEquals(SourceResult.FIRED, await(sources(o), spec(WaitKind.TAB_TEXT, "all done", from)))
        tab.rows = rows(400..450); tab.lastOutputNanos = 4
        assertNull(await(sources(o), spec(WaitKind.TAB_TEXT, "all done", from), ms = 150))
    }

    @Test fun `a wait on a tab that is gone ends`() {
        val tab = Tab(1, null, "x"); tab.missing = true
        val o = owner(tab)
        assertEquals(SourceResult.GONE, await(sources(o), spec(WaitKind.TAB_TEXT, "x")))
        assertEquals(SourceResult.GONE, await(sources(o), spec(WaitKind.TAB_QUIET)))
    }

    @Test fun `tab_quiet takes seconds of silence, ten by default`() {
        val tab = Tab(1, null, "\$ ")
        val s = sources(owner(tab))
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
        val tab = Tab(1, null, "\$ ls")
        val o = owner(tab)
        val s = sources(o)
        val from = cursorOf(read(o).text)
        // The command printed its output at 1 s and nothing since; the wait is set at 60 s.
        tab.rows = "\$ ls\nfile a\nfile b\n\$ "; tab.lastOutputNanos = secs(1); clock.set(secs(60))
        assertEquals(SourceResult.FIRED, await(s, spec(WaitKind.TAB_QUIET, from = from, seconds = 5)))
        // Without the cursor the silence counts from the start of the wait: not quiet yet at its start.
        assertNull(await(s, spec(WaitKind.TAB_QUIET, seconds = 5), ms = 100))
        // With the cursor but nothing printed after it, also from the start of the wait.
        val none = cursorOf(read(o).text)
        assertNull(await(s, spec(WaitKind.TAB_QUIET, from = none, seconds = 5), ms = 100))
    }
}
