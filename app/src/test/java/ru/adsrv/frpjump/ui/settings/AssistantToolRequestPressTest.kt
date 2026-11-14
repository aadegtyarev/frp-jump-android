package ru.adsrv.frpjump.ui.settings

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolResult
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.chat.ChatState
import ru.adsrv.frpjump.assistant.chat.ChatToolReceipt
import ru.adsrv.frpjump.assistant.chat.ChatTurn
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.TextStorage

/** Р56: what "Turn on" does, and when the buttons show. */
class AssistantToolRequestPressTest {
    private class Queue : CoroutineDispatcher() {
        val work = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.add(block) }
        fun drain() { while (work.isNotEmpty()) checkNotNull(work.poll()).run() }
    }
    private class Memory : TextStorage {
        @Volatile var text: String? = null
        var fail = false
        override fun read() = text
        override fun write(text: String) { if (fail) error("synthetic private failure"); this.text = text }
        override fun delete() { text = null }
    }
    private class Fx : AutoCloseable {
        val file = Memory()
        val store = SettingsStore(file)
        val main = Queue(); val io = Queue()
        val scope = CoroutineScope(SupervisorJob() + main)
        val binding = AssistantToolsBinding(store, scope, io)
        val sent = ArrayList<String>()
        val request = AssistantToolRequest(store, binding, scope) { sent += it }
        init { main.drain() }
        fun press(id: AssistantToolId, message: String = "Turned on ${id.wire}") { request.turnOn(id, message); main.drain(); io.drain(); main.drain() }
        fun on() = AssistantToolId.entries.filter { store.assistantAvailability.tools[it] }
        override fun close() { scope.cancel(); main.drain() }
    }

    @Test fun `without a press nothing is turned on and nothing is sent`() {
        Fx().use { f ->
            f.main.drain(); f.io.drain(); f.main.drain()
            assertEquals(emptyList<AssistantToolId>(), f.on()); assertEquals(emptyList<String>(), f.sent)
        }
    }

    @Test fun `Turn on switches on exactly that tool, persisted, then sends the fixed message once`() {
        for (id in AssistantToolId.entries) Fx().use { f ->
            f.press(id, "Turned on ${id.wire}")
            assertEquals(listOf(id), f.on())
            assertEquals(listOf("Turned on ${id.wire}"), f.sent)
            assertEquals("the same persisted switch as Tools", listOf(id), AssistantToolId.entries.filter { SettingsStore(f.file).assistantAvailability.tools[it] })
        }
    }

    @Test fun `a second press while one runs, or for a tool that is already on, sends nothing more`() {
        Fx().use { f ->
            f.request.turnOn(AssistantToolId.READ_PAGE, "m"); f.request.turnOn(AssistantToolId.READ_PAGE, "m"); f.request.turnOn(AssistantToolId.SSH_COMMAND, "other")
            f.main.drain(); f.io.drain(); f.main.drain()
            assertEquals(listOf("m"), f.sent); assertEquals(listOf(AssistantToolId.READ_PAGE), f.on())
            f.press(AssistantToolId.READ_PAGE, "again")
            assertEquals(listOf("m"), f.sent)
        }
    }

    @Test fun `nothing is sent when the switch could not be saved`() {
        Fx().use { f ->
            f.file.fail = true
            f.press(AssistantToolId.SSH_COMMAND)
            assertEquals(emptyList<String>(), f.sent); assertEquals(emptyList<AssistantToolId>(), f.on())
            f.file.fail = false
            f.press(AssistantToolId.SSH_COMMAND)
            assertEquals(listOf("Turned on ssh_command"), f.sent)
        }
    }

    @Test fun `a failed press shows the tool's save error, keeps the buttons and sends nothing`() {
        Fx().use { f ->
            f.file.fail = true
            f.press(AssistantToolId.SSH_COMMAND)
            assertTrue("ssh_command" in f.binding.state.value.failed)
            assertEquals(emptyList<String>(), f.sent); assertEquals(emptyList<AssistantToolId>(), f.on())
            // The chat shows the failure for that key next to the buttons, which are still there.
            assertEquals(listOf(AssistantToolId.SSH_COMMAND), requestedTools(ChatState(turns = listOf(ChatTurn("u", "a", askedTools = listOf("ssh_command")))), true, f.store.assistantAvailability.tools))
            f.file.fail = false
            f.press(AssistantToolId.SSH_COMMAND)
            assertFalse("ssh_command" in f.binding.state.value.failed)
            assertEquals(listOf("Turned on ssh_command"), f.sent)
        }
    }

    @Test fun `the offer switch is saved through the same binding and hides the buttons`() {
        Fx().use { f ->
            assertTrue(f.binding.state.value.committed.offer)
            f.binding.changeOffer(false); f.main.drain(); f.io.drain(); f.main.drain()
            assertFalse(f.binding.state.value.committed.offer); assertFalse(SettingsStore(f.file).assistantAvailability.tools.offer)
            assertTrue(OFFER_KEY !in f.binding.state.value.pending)
            val asked = ChatState(turns = listOf(ChatTurn("u", "a", askedTools = listOf("ssh_command"))))
            assertTrue(requestedTools(asked, true, f.binding.state.value.committed).isEmpty())
            f.file.fail = true
            f.binding.changeOffer(true); f.main.drain(); f.io.drain(); f.main.drain()
            assertTrue(OFFER_KEY in f.binding.state.value.failed); assertFalse(f.binding.state.value.committed.offer)
            f.file.fail = false
            f.binding.changeOffer(true); f.main.drain(); f.io.drain(); f.main.drain()
            assertTrue(f.binding.state.value.committed.offer); assertEquals(listOf(AssistantToolId.SSH_COMMAND), requestedTools(asked, true, f.binding.state.value.committed))
        }
    }

    // ---- When the buttons show ----

    private fun turn(asked: List<String>) = ChatTurn("u", "answer", askedTools = asked)
    private fun state(asked: List<String> = listOf("ssh_command", "read_page"), busy: Boolean = false, declined: Set<String> = emptySet(), pending: String? = null,
        turns: List<ChatTurn> = listOf(turn(listOf("older")), turn(asked))) = ChatState(turns = turns, busy = busy, declinedTools = declined, pendingText = pending)

    @Test fun `the buttons are for the latest reply's asked tools that are off and not declined`() {
        assertEquals(listOf(AssistantToolId.SSH_COMMAND, AssistantToolId.READ_PAGE), requestedTools(state(), true, AssistantTools()))
        assertEquals(listOf(AssistantToolId.SSH_COMMAND), requestedTools(state(), true, AssistantTools(readPage = true)))
        assertEquals(listOf(AssistantToolId.READ_PAGE), requestedTools(state(), true, AssistantTools(sshCommand = true)))
        assertEquals(listOf(AssistantToolId.READ_PAGE), requestedTools(state(declined = setOf("ssh_command")), true, AssistantTools()))
    }

    @Test fun `no buttons while an attempt runs, with the assistant off, for tools that are on, after a new Send or a clear`() {
        assertTrue(requestedTools(state(busy = true), true, AssistantTools()).isEmpty())
        assertTrue(requestedTools(state(pending = "next"), true, AssistantTools()).isEmpty())
        assertTrue(requestedTools(state(), false, AssistantTools()).isEmpty())
        assertTrue(requestedTools(state(), true, AssistantTools(readPage = true, sshCommand = true)).isEmpty())
        assertTrue("after a clear the chat is empty", requestedTools(ChatState(), true, AssistantTools()).isEmpty())
        assertTrue(requestedTools(ChatState(turns = listOf(turn(listOf("ssh_command"))), clearing = true), true, AssistantTools()).isEmpty())
        assertTrue(requestedTools(state(declined = setOf("ssh_command", "read_page")), true, AssistantTools()).isEmpty())
        // After a new Send that finished without asking, the latest turn asked nothing: the old ask is gone.
        assertTrue(requestedTools(state(turns = listOf(turn(listOf("ssh_command")), turn(emptyList()))), true, AssistantTools()).isEmpty())
    }
}
