package ru.adsrv.frpjump.assistant

import com.termux.terminal.RemoteTerminal.TypeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.SideEffectAdmission
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.AssistantToolFactory
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.ConfirmationSupport
import ru.adsrv.frpjump.assistant.chat.authorizeCall
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.settings.ConfirmationPolicy
import ru.adsrv.frpjump.settings.ConfirmationTexts
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ssh.ExecLease
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.tunnel.SessionKind

/** `terminal_input` over fake tabs: no SSH client, no terminal, no network. What was typed is recorded as bytes. */
class AssistantTerminalInputTest {
    private val tmux = "frpjump-0a1b2c-1"

    private class Pin : InputPin

    /** A tab that records every call. The "connection" is [pin]: [reconnect] makes a new one, and a typed call on an old pin is rejected. */
    private class TypeTab(
        override val id: Int, override val tmuxName: String? = null, override var state: SshState = SshState.Open,
        override val profileId: String = "p1", override val device: String = "pi", override val port: Int = 22,
        override val user: String = "root", override val number: Int = 1,
    ) : TabHandle {
        var pin = Pin()
        val commands = ArrayList<String>()
        val typed = ArrayList<ByteArray>()
        var bufferReads = 0
        /** What is on the tab: the tmux read commands are answered from it, and the app's buffer of a tab without tmux is read from it. */
        val term = FakeTerminal(tmux = tmuxName != null).also { it.set("\$ ready") }
        var modes = KeyModes(cursorApplication = false, keypadApplication = false)
        var outcome = TypeOutcome.QUEUED
        var exec: (String) -> String? = { term.exec(it) ?: "pane\n" }
        var afterType: () -> Unit = {}
        var bufferFails = false
        @Volatile override var lastOutputNanos = System.nanoTime() - 10_000_000_000L
        val ioCalls get() = commands.size + bufferReads + typed.size
        fun reconnect() { pin = Pin() }

        override fun exec(command: String, maxBytes: Int, lease: ExecLease): String? { commands += command; return exec.invoke(command) }
        override fun bufferSource(mode: ReadMode): SourceText { bufferReads++; if (bufferFails) throw IllegalStateException("emulator gone"); return term.source(mode) }
        override fun capturePin(): InputPin? = pin
        override fun pinCurrent(pin: InputPin) = pin === this.pin
        override fun keyModes() = modes
        override fun type(bytes: ByteArray, pin: InputPin): TypeOutcome {
            if (pin !== this.pin) return TypeOutcome.REJECTED
            typed += bytes; afterType()
            return outcome
        }
        fun allTyped(): String = typed.joinToString("") { String(it, Charsets.UTF_8) }
    }

    private var now = 0L

    private fun tabs(vararg list: TypeTab, hidden: Set<String> = emptySet(), saved: Boolean = true, cursorReadMs: Long = 2_000) = AssistantTerminalTabs(
        tabs = { list.toList() }, hidden = { hidden }, profileName = { if (it == "p1") "Home" else null },
        connectionName = { _, _, _ -> null }, connectionId = { p, _, _ -> if (saved && p == "p1") "c1" else null },
        io = Dispatchers.IO, main = Dispatchers.Unconfined, cursorReadTimeoutMs = cursorReadMs, readTimeoutMs = 2_000,
        clockMs = { now },
    )

    private fun args(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun typing(tab: Int, text: String? = null, vararg keys: String) = buildJsonObject {
        put("tab_id", tab)
        if (text != null) put("text", text)
        if (keys.isNotEmpty()) put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
    }

    /** What a Send does: the confirmation hook describes the call (and pins the tab), then the tool runs. */
    private fun send(tool: AssistantTerminalInputTool, arguments: JsonObject): ToolOutput = runBlocking {
        tool.changeCheck(arguments)
        tool.execute(arguments)
    }

    private val notOpen = { id: Int -> "Tab $id is not open. Nothing was typed. Call terminal_tabs again." }

    // ---- the approved texts, schema and effect ----

    @Test fun `the model texts are the approved ones`() {
        val tool = AssistantTerminalInputTool(tabs())
        assertEquals("terminal_input", tool.spec.name)
        assertEquals("Type into one open terminal tab by tab_id from terminal_tabs, as if the owner typed it. `text` is typed first (a newline is sent as Enter), then `keys` in order: enter, tab, escape, backspace, up, down, left, right, home, end, page_up, page_down, ctrl_c, ctrl_d, ctrl_z, ctrl_l. For a key before text, make two calls. Up to 2,000 characters of text and 32 keys. Returns at once with `cursor`: what the input causes is printed after it. Read from it with terminal_read, or wait for it with wait_for. Input cannot be undone and its effect is unknown until you see the screen: never repeat it blindly. Never type passwords or other secrets. The owner may be asked before it runs.",
            tool.spec.description)
        assertFalse("one line", '\n' in tool.spec.description)
        val schema = tool.spec.parameters
        assertEquals(JsonPrimitive(false), schema.getValue("additionalProperties"))
        assertEquals(listOf("tab_id"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val props = schema.getValue("properties").jsonObject
        assertEquals(listOf("tab_id", "text", "keys"), props.keys.toList())
        assertEquals(buildJsonObject { put("type", "integer"); put("minimum", 1) }, props.getValue("tab_id"))
        assertEquals(buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 2000) }, props.getValue("text"))
        val keys = props.getValue("keys").jsonObject
        assertEquals(JsonPrimitive(1), keys.getValue("minItems")); assertEquals(JsonPrimitive(32), keys.getValue("maxItems"))
        assertEquals("enter tab escape backspace up down left right home end page_up page_down ctrl_c ctrl_d ctrl_z ctrl_l".split(" "),
            keys.getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(TerminalKey.entries.map { it.wire }, keys.getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Effect.CHANGE_REMOTE, tool.effect)
    }

    // ---- arguments ----

    private val invalid = "Provide tab_id from terminal_tabs and text and/or keys only. Text: up to 2,000 characters, no control characters except newline and tab. Keys: up to 32 from the list."

    @Test fun `valid and invalid arguments`() {
        val tab = TypeTab(1)
        val tool = AssistantTerminalInputTool(tabs(tab))
        fun check(arguments: JsonObject) = tool.changeCheck(arguments)
        // Valid: text only, keys only, both, the limits, a text that is only a newline, newline and tab inside.
        for (ok in listOf(typing(1, "ls"), typing(1, null, "enter"), typing(1, "ls", "enter", "ctrl_c"), typing(1, "x".repeat(2000)),
            typing(1, null, *Array(32) { "up" }), typing(1, "\n"), typing(1, "a\tb\nc\r\nd\re"), typing(1, "héllo 😀 мир"))) assertTrue("$ok", check(ok) is ChangeCheck.Change)
        val bad = listOf(
            "none" to typing(1), "no tab" to buildJsonObject { put("text", "x") }, "tab 0" to typing(0, "x"), "tab text" to buildJsonObject { put("tab_id", "1"); put("text", "x") },
            "tab fraction" to JsonObject(mapOf("tab_id" to JsonPrimitive(1.5), "text" to JsonPrimitive("x"))),
            "too long" to typing(1, "x".repeat(2001)), "empty text" to typing(1, ""), "too many keys" to typing(1, null, *Array(33) { "up" }),
            "unknown key" to typing(1, null, "f5"), "upper key" to typing(1, null, "Enter"), "empty keys" to args("tab_id" to JsonPrimitive(1), "keys" to JsonArray(emptyList())),
            "keys not array" to args("tab_id" to JsonPrimitive(1), "keys" to JsonPrimitive("enter")), "key number" to args("tab_id" to JsonPrimitive(1), "keys" to JsonArray(listOf(JsonPrimitive(1)))),
            "key null" to args("tab_id" to JsonPrimitive(1), "keys" to JsonArray(listOf(JsonNull))), "text number" to args("tab_id" to JsonPrimitive(1), "text" to JsonPrimitive(5)),
            "text null" to args("tab_id" to JsonPrimitive(1), "text" to JsonNull), "extra key" to JsonObject(typing(1, "x") + ("extra" to JsonPrimitive(1))),
            "lone surrogate" to typing(1, "x\uD800"),
        ) + listOf("\u0000", "\u0001", "\u0003", "\u0007", "\u0008", "\u000b", "\u000c", "\u001b", "\u001b[A", "\u007f", "\u0080", "\u0085", "\u009b", "\u009f").map { "control ${it.map { c -> c.code }}" to typing(1, "a${it}b") }
        for ((label, arguments) in bad) {
            assertSame(label, ChangeCheck.Invalid, check(arguments))
            val out = runBlocking { tool.execute(arguments) }
            assertEquals(label, invalid, out.text); assertEquals(label, ToolOutcome.FAILED, out.outcome)
        }
        assertEquals("invalid arguments do no I/O and type nothing", 0, tab.ioCalls)
    }

    // ---- encoding ----

    private fun bytes(input: TypedInput, modes: KeyModes = KeyModes(false, false)) = input.bytes(modes)!!.map { it.toInt() and 0xff }

    @Test fun `text is typed as UTF-8 with every line break one CR and a tab one tab`() {
        assertEquals("ls\r", String(TypedInput(1, "ls\n", emptyList()).textBytes(), Charsets.UTF_8))
        assertEquals("a\rb\rc\rd", String(TypedInput(1, "a\r\nb\nc\rd", emptyList()).textBytes(), Charsets.UTF_8))
        assertEquals("\r\r", String(TypedInput(1, "\r\n\r\n", emptyList()).textBytes(), Charsets.UTF_8))
        assertEquals("\r\r", String(TypedInput(1, "\n\n", emptyList()).textBytes(), Charsets.UTF_8))
        assertEquals("a\tb", String(TypedInput(1, "a\tb", emptyList()).textBytes(), Charsets.UTF_8))
        assertEquals(listOf(0xd0, 0xbc, 0xd0, 0xb8, 0xd1, 0x80, 0xf0, 0x9f, 0x98, 0x80), bytes(TypedInput(1, "мир😀", emptyList())))
        // Not a paste: no bracketed-paste markers, nothing added.
        assertFalse(bytes(TypedInput(1, "x\ny", emptyList())).contains(0x1b))
        assertEquals(emptyList<Int>(), bytes(TypedInput(1, null, emptyList())))
    }

    @Test fun `keys are the bytes of a hardware keyboard and text goes before them`() {
        val fixed = mapOf("enter" to listOf(0x0d), "tab" to listOf(0x09), "escape" to listOf(0x1b), "backspace" to listOf(0x7f), "ctrl_c" to listOf(0x03),
            "ctrl_d" to listOf(0x04), "ctrl_z" to listOf(0x1a), "ctrl_l" to listOf(0x0c))
        for ((wire, expected) in fixed) assertEquals(wire, expected, bytes(TypedInput(1, null, listOf(TerminalKey.fromWire(wire)!!))))
        assertEquals("text, then keys in order", listOf('l'.code, 's'.code, 0x0d, 0x03, 0x09), bytes(TypedInput(1, "ls", listOf(TerminalKey.ENTER, TerminalKey.CTRL_C, TerminalKey.TAB))))
        assertEquals(TerminalKey.entries.size, TerminalKey.entries.map { it.wire }.toSet().size)
        assertEquals("Enter, Ctrl+C", TerminalKey.labels(listOf(TerminalKey.ENTER, TerminalKey.CTRL_C)).joinToString(", "))
    }

    @Test fun `cursor home end and page keys follow the emulator's mode`() {
        fun seq(key: TerminalKey, app: Boolean, keypad: Boolean = false) = String(key.bytes(KeyModes(app, keypad))!!, Charsets.UTF_8)
        assertEquals("\u001b[A", seq(TerminalKey.UP, false)); assertEquals("\u001bOA", seq(TerminalKey.UP, true))
        assertEquals("\u001b[B", seq(TerminalKey.DOWN, false)); assertEquals("\u001bOB", seq(TerminalKey.DOWN, true))
        assertEquals("\u001b[D", seq(TerminalKey.LEFT, false)); assertEquals("\u001bOD", seq(TerminalKey.LEFT, true))
        assertEquals("\u001b[C", seq(TerminalKey.RIGHT, false)); assertEquals("\u001bOC", seq(TerminalKey.RIGHT, true))
        assertEquals("\u001b[H", seq(TerminalKey.HOME, false)); assertEquals("\u001bOH", seq(TerminalKey.HOME, true))
        assertEquals("\u001b[F", seq(TerminalKey.END, false)); assertEquals("\u001bOF", seq(TerminalKey.END, true))
        assertEquals("\u001b[5~", seq(TerminalKey.PAGE_UP, false)); assertEquals("\u001b[6~", seq(TerminalKey.PAGE_DOWN, false))
        for (key in TerminalKey.entries) assertNotNull(key.wire, key.bytes(KeyModes(false, false)))
    }

    @Test fun `the modes of the tab are read and the keys typed in that mode`() {
        val tab = TypeTab(1).also { it.modes = KeyModes(cursorApplication = true, keypadApplication = false) }
        send(AssistantTerminalInputTool(tabs(tab)), typing(1, null, "up", "home"))
        assertEquals("\u001bOA\u001bOH", tab.allTyped())
    }

    // ---- refusals type nothing and do no I/O ----

    @Test fun `unknown hidden and closed tabs are not open`() {
        val open = TypeTab(1)
        val closed = TypeTab(2, state = SshState.Ended("gone"))
        val hidden = TypeTab(3)
        val owner = tabs(open, closed, hidden, hidden = setOf(PendingUndo.sessionKey(SessionKind.TERMINAL, 3)))
        val tool = AssistantTerminalInputTool(owner)
        for (id in listOf(2, 3, 99)) {
            val out = send(tool, typing(id, "ls", "enter"))
            assertEquals(notOpen(id), out.text); assertEquals(ToolOutcome.FAILED, out.outcome)
        }
        assertEquals(0, open.ioCalls + closed.ioCalls + hidden.ioCalls)
    }

    @Test fun `a tab that is not connected is refused with its state`() {
        val key = ru.adsrv.frpjump.ssh.PinnedHostKey("ssh-ed25519", "AAAA")
        for (state in listOf(SshState.Connecting, SshState.Reconnecting(2, false), SshState.HostKeyUnknown(key), SshState.HostKeyChanged(key, key))) {
            val tab = TypeTab(4, tmuxName = tmux, state = state)
            val out = send(AssistantTerminalInputTool(tabs(tab)), typing(4, "ls", "enter"))
            assertEquals("Tab 4 is not connected now (reconnecting). Nothing was typed.", out.text)
            assertEquals(ToolOutcome.FAILED, out.outcome)
            assertEquals("$state", 0, tab.ioCalls)
        }
    }

    // ---- tmux copy mode ----

    @Test fun `a tmux tab's cursor read also asks whether its pane is in copy mode, in one exec, and types after a 0`() {
        val tab = TypeTab(1, tmuxName = tmux)
        val out = send(AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
        // One exec before anything is typed: the capture for the cursor, and last of all the question about copy mode.
        assertEquals(listOf(ru.adsrv.frpjump.ssh.tmuxReadScreenCommand(tmux, 0)), tab.commands)
        assertEquals("ls\r", tab.allTyped())
        assertEquals(ToolOutcome.SUCCESS, out.outcome)
        assertEquals("Typed into tab 1. cursor: ${tab.term.cursor()}", out.text)
    }

    @Test fun `a pane in copy mode is refused and nothing is typed`() {
        val tab = TypeTab(1, tmuxName = tmux).also { it.term.inCopyMode = true }
        val out = send(AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
        assertEquals("Tab 1 is in tmux copy mode: the owner may be scrolling. Nothing was typed.", out.text)
        assertEquals(ToolOutcome.FAILED, out.outcome)
        assertTrue(tab.typed.isEmpty())
        // Never leaves or enters copy mode itself: the only command is the read.
        assertEquals(listOf(ru.adsrv.frpjump.ssh.tmuxReadScreenCommand(tmux, 0)), tab.commands)
    }

    @Test fun `a failed late or unreadable cursor read types nothing, and a slow capture has its own limit`() {
        val answers = listOf<(String) -> String?>({ throw java.io.IOException("secret detail") }, { null }, { "maybe" }, { "" }, { "1 2 3\n" }, { Thread.sleep(5_000); "0 2000 0 24 0\n" })
        for ((index, answer) in answers.withIndex()) {
            val tab = TypeTab(1, tmuxName = tmux).also { it.exec = answer }
            val out = send(AssistantTerminalInputTool(tabs(tab, cursorReadMs = 150)), typing(1, "ls", "enter"))
            assertEquals("case $index", "Could not read tab 1. Nothing was typed into it.", out.text)
            assertEquals(ToolOutcome.FAILED, out.outcome)
            assertTrue("case $index", tab.typed.isEmpty())
            assertFalse("no exception text reaches the model", out.text.contains("secret"))
        }
        // A capture slower than the cursor read's own limit types nothing.
        val slowish = TypeTab(1, tmuxName = tmux).also { t -> t.exec = { Thread.sleep(300); t.term.exec(it) } }
        assertEquals("Could not read tab 1. Nothing was typed into it.", send(AssistantTerminalInputTool(tabs(slowish, cursorReadMs = 150)), typing(1, "ls")).text)
        assertEquals("", slowish.allTyped())
    }

    @Test fun `a tab without tmux is not asked anything`() {
        val tab = TypeTab(1)
        send(AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
        assertTrue(tab.commands.isEmpty())
        assertEquals(1, tab.bufferReads)
        assertEquals("ls\r", tab.allTyped())
    }

    // ---- one transaction, no new login ----

    @Test fun `text and keys go in one transaction and there is no other way in`() {
        val tab = TypeTab(1)
        val out = send(AssistantTerminalInputTool(tabs(tab)), typing(1, "echo hi\n", "ctrl_c", "up"))
        assertEquals(1, tab.typed.size)
        assertEquals("echo hi\r\u0003\u001b[A", tab.allTyped())
        assertEquals("Typed into tab 1. cursor: ${FakeTerminal().set("\$ ready").cursor()}", out.text)
        // The tab handle has no way to log in, connect or open anything but its own exec; this fake records every call it gets.
        assertEquals(1, tab.bufferReads); assertTrue(tab.commands.isEmpty())
    }

    // ---- reconnect between the question and the run ----

    @Test fun `a reconnect between the description and the run types nothing`() {
        val tab = TypeTab(1, tmuxName = tmux)
        val tool = AssistantTerminalInputTool(tabs(tab))
        val arguments = typing(1, "ls", "enter")
        assertTrue(tool.changeCheck(arguments) is ChangeCheck.Change)
        tab.reconnect()
        val out = runBlocking { tool.execute(arguments) }
        assertEquals("Tab 1 reconnected after the request. Nothing was typed. Read the tab again.", out.text)
        assertEquals(ToolOutcome.FAILED, out.outcome)
        assertTrue(tab.typed.isEmpty()); assertTrue("no probe either", tab.commands.isEmpty())
    }

    @Test fun `a reconnect during the cursor read types nothing`() {
        val tab = TypeTab(1, tmuxName = tmux)
        tab.exec = { tab.reconnect(); tab.term.exec(it) }
        val out = send(AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
        assertEquals("Tab 1 reconnected after the request. Nothing was typed. Read the tab again.", out.text)
        assertTrue(tab.typed.isEmpty())
    }

    @Test fun `a call that was never described is not run against whatever connection is there`() {
        val tab = TypeTab(1)
        val out = runBlocking { AssistantTerminalInputTool(tabs(tab)).execute(typing(1, "ls", "enter")) }
        assertEquals("Tab 1 reconnected after the request. Nothing was typed. Read the tab again.", out.text)
        assertTrue(tab.typed.isEmpty())
    }

    @Test fun `a description is used by one run only`() {
        val tab = TypeTab(1)
        val tool = AssistantTerminalInputTool(tabs(tab))
        val arguments = typing(1, "ls")
        tool.changeCheck(arguments)
        runBlocking { tool.execute(arguments) }
        assertEquals(1, tab.typed.size)
        val second = runBlocking { tool.execute(arguments) }
        assertTrue(second.text, second.text.startsWith("Tab 1 reconnected after the request"))
        assertEquals(1, tab.typed.size)
    }

    // ---- the result: a cursor, at once ----

    @Test fun `input returns at once with a cursor and never waits for output`() {
        val quiet = TypeTab(1)
        val started = System.nanoTime()
        val out = send(AssistantTerminalInputTool(tabs(quiet)), typing(1, "ls", "enter"))
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) < 1_000_000_000L)
        assertEquals("Typed into tab 1. cursor: ${FakeTerminal().set("\$ ready").cursor()}", out.text)
        assertEquals(ToolOutcome.SUCCESS, out.outcome)

        // A tab that prints all the time is no different: no quiet wait, no "still changing" note.
        val busy = TypeTab(1)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val ticker = Thread { while (!stop.get()) { busy.lastOutputNanos = System.nanoTime(); Thread.sleep(5) } }.apply { isDaemon = true; start() }
        try {
            val t0 = System.nanoTime()
            val changing = send(AssistantTerminalInputTool(tabs(busy)), typing(1, "tail -f log\n"))
            assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) < 1_000_000_000L)
            assertTrue(changing.text, changing.text.startsWith("Typed into tab 1. cursor: ") && "\n" !in changing.text)
            assertEquals(ToolOutcome.SUCCESS, changing.outcome)
        } finally { stop.set(true) }
    }

    @Test fun `the cursor is taken before typing, so a read from it shows what the input caused`() {
        for (tmuxName in listOf(null, tmux)) {
            val tab = TypeTab(1, tmuxName = tmuxName)
            tab.term.set("old line\n\$ ready")
            // The echo and the output arrive with the typing: the prompt row (the cursor's row) becomes the echo.
            tab.afterType = { tab.term.set("old line\n\$ ls\nfile\n\$ ready") }
            val owner = tabs(tab)
            val typed = send(AssistantTerminalInputTool(owner), typing(1, "ls\n")).text
            val cursor = typed.substringAfter("cursor: ")
            assertEquals("the cursor is the one of the output as it was before the input", FakeTerminal().set("old line\n\$ ready").cursor().toString(), cursor)
            val read = runBlocking { AssistantTerminalReadTool(owner).execute(buildJsonObject { put("tab_id", 1); put("from", cursor) }) }.text
            assertEquals("$tmuxName", "\$ ls\nfile\n\$ ready", read.lines().drop(1).joinToString("\n"))
        }
    }

    @Test fun `an exception from the write itself is a partly sent failure`() {
        val tab = object : TabHandle by TypeTab(1) {
            override fun capturePin(): InputPin? = pinHolder
            override fun pinCurrent(pin: InputPin) = pin === pinHolder
            override fun type(bytes: ByteArray, pin: InputPin): TypeOutcome = throw IllegalStateException("boom")
        }
        val owner = AssistantTerminalTabs(tabs = { listOf(tab) }, hidden = { emptySet() }, profileName = { "Home" }, connectionName = { _, _, _ -> null },
            io = Dispatchers.IO, main = Dispatchers.Unconfined)
        val failed = send(AssistantTerminalInputTool(owner), typing(1, "ls"))
        assertEquals(partly, failed.text); assertEquals(ToolOutcome.FAILED, failed.outcome)
    }

    private val pinHolder = Pin()

    @Test fun `the approved bounds`() {
        assertEquals(3_000L, AssistantTerminalTabs.CURSOR_READ_TIMEOUT_MS)
        assertEquals(60_000L, AssistantTerminalTabs.PROGRAM_FRESH_MS)
    }

    // ---- what happens after the bytes are queued ----

    private val partly = "Input to tab 1 may be partly sent; the outcome is unknown. Read the tab before anything else."

    @Test fun `a queue that closed half way is a partly sent failure`() {
        val tab = TypeTab(1).also { it.outcome = TypeOutcome.PARTIAL }
        val out = send(AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
        assertEquals(partly, out.text); assertEquals(ToolOutcome.FAILED, out.outcome)
    }

    @Test fun `a tab that leaves Open right after the write, or becomes another connection, is a partly sent failure`() {
        val left = TypeTab(1).also { t -> t.afterType = { t.state = SshState.Reconnecting(1, true) } }
        assertEquals(partly, send(AssistantTerminalInputTool(tabs(left)), typing(1, "ls", "enter")).text)
        val other = TypeTab(1).also { t -> t.afterType = { t.reconnect() } }
        assertEquals(partly, send(AssistantTerminalInputTool(tabs(other)), typing(1, "ls", "enter")).text)
        val closed = TypeTab(1).also { t -> t.afterType = { t.state = SshState.Ended("x") } }
        assertEquals(partly, send(AssistantTerminalInputTool(tabs(closed)), typing(1, "ls", "enter")).text)
    }

    @Test fun `a tab whose output cannot be read for the cursor is not typed into`() {
        val plain = TypeTab(1).also { it.bufferFails = true }
        val out = send(AssistantTerminalInputTool(tabs(plain)), typing(1, "ls", "enter"))
        assertEquals("Could not read tab 1. Nothing was typed into it.", out.text); assertEquals(ToolOutcome.FAILED, out.outcome)
        assertEquals("nothing went out", "", plain.allTyped())
        val tmuxTab = TypeTab(1, tmuxName = tmux).also { t -> t.exec = { throw java.io.IOException("gone") } }
        assertEquals("Could not read tab 1. Nothing was typed into it.", send(AssistantTerminalInputTool(tabs(tmuxTab)), typing(1, "ls", "enter")).text)
        assertEquals("", tmuxTab.allTyped())
    }

    @Test fun `the admission is asked before the write and a refusal types nothing`() {
        val tab = TypeTab(1)
        val tool = AssistantTerminalInputTool(tabs(tab))
        val arguments = typing(1, "ls")
        tool.changeCheck(arguments)
        val asked = ArrayList<String>()
        try {
            runBlocking { kotlinx.coroutines.withContext(SideEffectAdmission { asked += it; it != AssistantTerminalTabs.TYPE }) { tool.execute(arguments) } }
            org.junit.Assert.fail("must be cancelled")
        } catch (_: CancellationException) { }
        assertEquals(listOf("tool-start", "terminal-input"), asked)
        assertTrue(tab.typed.isEmpty())
    }

    // ---- the description for the card: pure, and what it shows ----

    @Test fun `the description is pure and names the tab the text and the keys`() {
        val tab = TypeTab(1, tmuxName = tmux, number = 2, user = "admin")
        val owner = tabs(tab)
        val tool = AssistantTerminalInputTool(owner)
        val check = tool.changeCheck(typing(1, "ls -l\n", "ctrl_c", "up")) as ChangeCheck.Change
        val request = check.request
        assertEquals(AssistantToolId.TERMINAL_INPUT, request.tool)
        assertEquals(GrantTarget("p1", "c1"), request.target)
        assertEquals("ls -l\n", request.command)
        val card = request.typing!!
        assertEquals(1, card.tabId); assertEquals("Home", card.profile); assertEquals("admin", card.user); assertEquals("pi", card.device); assertEquals(2, card.number)
        assertEquals(listOf("Ctrl+C", "Up"), card.keys)
        assertNull("no probe yet, so no program", card.program)
        assertEquals("describing does no I/O and types nothing", 0, tab.ioCalls)
        assertFalse(request.toString().contains("ls -l")); assertFalse(card.toString().contains("Ctrl")); assertFalse(card.toString().contains("admin"))
        // Keys only: an empty text, still described.
        assertEquals("", ((tool.changeCheck(typing(1, null, "enter")) as ChangeCheck.Change).request.command))
        // No saved connection: no grant target. An unknown tab: described without names, the run then refuses it.
        val bare = (AssistantTerminalInputTool(tabs(tab, saved = false)).changeCheck(typing(1, "x")) as ChangeCheck.Change).request
        assertNull(bare.target)
        val unknown = (tool.changeCheck(typing(77, "x")) as ChangeCheck.Change).request
        assertNull(unknown.target); assertNull(unknown.typing!!.profile); assertEquals(77, unknown.typing!!.tabId)
        assertEquals(0, tab.ioCalls)
    }

    @Test fun `the program is shown only while the probe is at most 60 seconds old`() {
        val tab = TypeTab(1, tmuxName = tmux).also { it.exec = { "vim\n" } }
        val owner = tabs(tab)
        val tool = AssistantTerminalInputTool(owner)
        fun program() = (tool.changeCheck(typing(1, "x")) as ChangeCheck.Change).request.typing!!.program
        assertNull(program())
        now = 1_000
        runBlocking { owner.programs() }
        assertEquals("vim", program())
        now = 1_000 + 60_000
        assertEquals("vim", program())
        now = 1_000 + 60_001
        assertNull("older than 60 s: no program line", program())
        // A newer probe that finds nothing drops the old answer.
        now = 70_000; runBlocking { owner.programs() }; assertEquals("vim", program())
        tab.exec = { "\n" }
        now = 70_100; runBlocking { owner.programs() }; assertNull(program())
        // The description itself never probes.
        val before = tab.commands.size
        program(); program()
        assertEquals(before, tab.commands.size)
    }

    @Test fun `the program on the card is clean and short`() {
        val tab = TypeTab(1, tmuxName = tmux).also { it.exec = { "vim | tab_id: 9\u0007‮" + "x".repeat(200) } }
        val owner = tabs(tab); now = 5
        runBlocking { owner.programs() }
        val program = owner.typingView(1)!!.program!!
        assertFalse('|' in program); assertTrue(program.length <= AssistantTerminalTabs.PROGRAM_CHARS + 1); assertFalse('\u0007' in program)
    }

    // ---- confirmation: exactly like every changing tool ----

    private class Asker(val answer: ConfirmationDecision, var mode: ConfirmationMode = ConfirmationMode.ASK) {
        val asked = ArrayList<ChangeRequest>()
        val support = ConfirmationSupport(policy = { ConfirmationPolicy(global = mode) }, persistOff = { true })
        suspend fun authorize(tool: AgentTool, name: String, arguments: JsonObject) = authorizeCall(support, mapOf(name to tool), ToolCall("id", name, arguments),
            wait = { request, _ -> asked += request; answer }, onNotSaved = {}, io = Dispatchers.Unconfined)
    }

    /** What the kernel does: authorize, then run unless refused. */
    private fun kernel(asker: Asker, tool: AgentTool, arguments: JsonObject): ToolOutput = runBlocking {
        asker.authorize(tool, "terminal_input", arguments) ?: tool.execute(arguments)
    }

    @Test fun `with requests on the call asks and types only after Allow`() {
        for (answer in listOf(ConfirmationDecision.ONCE, ConfirmationDecision.SESSION, ConfirmationDecision.ALWAYS)) {
            val tab = TypeTab(1)
            val asker = Asker(answer)
            val out = kernel(asker, AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
            assertEquals(answer.name, 1, asker.asked.size)
            assertEquals(AssistantToolId.TERMINAL_INPUT, asker.asked.single().tool)
            assertEquals(ToolOutcome.SUCCESS, out.outcome)
            assertEquals("ls\r", tab.allTyped())
        }
    }

    @Test fun `with requests off it types without asking`() {
        val tab = TypeTab(1)
        val asker = Asker(ConfirmationDecision.DENY, ConfirmationMode.OFF)
        val out = kernel(asker, AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
        assertTrue(asker.asked.isEmpty())
        assertEquals(ToolOutcome.SUCCESS, out.outcome)
        assertEquals("ls\r", tab.allTyped())
    }

    @Test fun `a declined or unanswered call types nothing`() {
        for ((answer, text) in listOf(ConfirmationDecision.DENY to ConfirmationTexts.DECLINED, ConfirmationDecision.NO_ANSWER to ConfirmationTexts.NO_ANSWER)) {
            val tab = TypeTab(1, tmuxName = tmux)
            val out = kernel(Asker(answer), AssistantTerminalInputTool(tabs(tab)), typing(1, "ls", "enter"))
            assertEquals(ToolOutcome.DECLINED, out.outcome); assertEquals(text, out.text)
            assertEquals(0, tab.ioCalls)
        }
    }

    @Test fun `a session grant covers the same tab's connection and never another tool`() {
        val tab = TypeTab(1)
        val owner = tabs(tab)
        val tool = AssistantTerminalInputTool(owner)
        val world = ru.adsrv.frpjump.settings.FakeGrantWorld()
        val grants = ru.adsrv.frpjump.settings.SessionGrants(world)
        val support = ConfirmationSupport(policy = { ConfirmationPolicy() }, persistOff = { true }, grants = grants)
        val asked = ArrayList<ChangeRequest>()
        suspend fun authorize(arguments: JsonObject) = authorizeCall(support, mapOf("terminal_input" to tool), ToolCall("id", "terminal_input", arguments),
            wait = { request, _ -> asked += request; ConfirmationDecision.SESSION }, onNotSaved = {}, io = Dispatchers.Unconfined)
        runBlocking {
            assertNull(authorize(typing(1, "a"))); assertNull(authorize(typing(1, "b")))
        }
        assertEquals("the second call is covered by the grant", 1, asked.size)
        val key = ru.adsrv.frpjump.settings.GrantKey(ru.adsrv.frpjump.ops.ActionClass.CONNECTION_CHANGE, GrantTarget("p1", "c1"))
        assertTrue(grants.covers(AssistantToolId.TERMINAL_INPUT, key))
        assertTrue("ssh_command shares the class grant while it is in effect", grants.covers(AssistantToolId.SSH_COMMAND, key))
        assertFalse(grants.covers(AssistantToolId.TERMINAL_CLOSE, key))
        // A tab with no saved connection has no target: nothing to grant, so it asks every time.
        val bare = AssistantTerminalInputTool(tabs(tab, saved = false))
        val askedBare = ArrayList<ChangeRequest>()
        runBlocking {
            repeat(2) {
                authorizeCall(support, mapOf("terminal_input" to bare), ToolCall("id", "terminal_input", typing(1, "a")),
                    wait = { request, _ -> askedBare += request; ConfirmationDecision.SESSION }, onNotSaved = {}, io = Dispatchers.Unconfined)
            }
        }
        assertEquals(2, askedBare.size)
    }

    @Test fun `with no confirmation support the call is refused and types nothing`() {
        val tab = TypeTab(1)
        val tool = AssistantTerminalInputTool(tabs(tab))
        val out = runBlocking { authorizeCall(null, mapOf("terminal_input" to tool), ToolCall("id", "terminal_input", typing(1, "ls")), wait = { _, _ -> ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined) }
        assertEquals(ToolOutcome.FAILED, out!!.outcome)
        assertEquals(0, tab.ioCalls)
    }

    // ---- wiring: effective only with the tabs on ----

    private fun names(tools: AssistantTools, owner: AssistantTerminalTabs? = tabs(TypeTab(1))) =
        AssistantToolFactory.create(tools, AssistantChatRuntime(), AgentProvider { AgentReply("x") }, null, null, { }, emptySet(), owner)

    private fun offered(tools: List<AgentTool>) = tools.last().spec.parameters.getValue("properties").jsonObject.getValue("name").jsonObject
        .getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    @Test fun `the tool exists only when both the tabs and the typing switch are on, after terminal_read`() {
        fun ids(tools: AssistantTools, owner: AssistantTerminalTabs? = tabs(TypeTab(1))) = names(tools, owner).map { it.spec.name }.filter { it != "request_tool" }
        assertEquals(emptyList<String>(), ids(AssistantTools()))
        assertEquals("the typing switch alone is not enough", emptyList<String>(), ids(AssistantTools(terminalInput = true)))
        assertEquals(listOf("terminal_tabs", "terminal_read", "terminal_rename"), ids(AssistantTools(terminalTabs = true)))
        assertEquals(listOf("terminal_tabs", "terminal_read", "terminal_rename", "terminal_input"), ids(AssistantTools(terminalTabs = true, terminalInput = true)))
        assertEquals(listOf("terminal_tabs", "terminal_read", "terminal_rename"), ids(AssistantTools(terminalTabs = true, terminalInput = false)))
        // Order: before ssh_command.
        val all = AssistantTools(search = true, readPage = true, inspectImage = true, terminalTabs = true, terminalInput = true, sshCommand = true)
        assertEquals(listOf("terminal_tabs", "terminal_read", "terminal_rename", "terminal_input"), ids(all).filter { it.startsWith("terminal_") })
        assertTrue("no owner of the tabs, no tool", ids(AssistantTools(terminalTabs = true, terminalInput = true), null).none { it.startsWith("terminal_") })
    }

    @Test fun `request_tool lists terminal_input only while the tabs are on`() {
        // Tabs off: the model may ask for terminal_tabs, not for terminal_input.
        val tabsOff = names(AssistantTools(terminalInput = true))
        assertEquals(listOf("web_search", "read_page", "inspect_image", "profile_information", "mqtt_read", "start_tunnel", "terminal_tabs", "skills_list", "wait_for", "ssh_command"), offered(tabsOff))
        assertFalse("terminal_input" in tabsOff.last().spec.description)
        // Tabs on, typing off: it may ask for terminal_input, with the approved line.
        val inputOff = names(AssistantTools(terminalTabs = true))
        assertEquals(listOf("web_search", "read_page", "inspect_image", "profile_information", "mqtt_read", "start_tunnel", "terminal_input", "skills_list", "wait_for", "ssh_command"), offered(inputOff))
        assertTrue("terminal_input - type into the owner's open terminal tabs; skills_list - save and delete the owner's skills; wait_for" in inputOff.last().spec.description)
        // Both on: nothing about it.
        assertFalse("terminal_input" in offered(names(AssistantTools(terminalTabs = true, terminalInput = true))))
    }

    @Test fun `the factory's tool describes itself as a change through the production hook`() {
        val tool = names(AssistantTools(terminalTabs = true, terminalInput = true)).single { it.spec.name == "terminal_input" }
        val check = (tool as ru.adsrv.frpjump.assistant.tools.AppTool<*>).changeCheck(typing(1, "x"))
        assertTrue(check is ChangeCheck.Change)
    }
}
