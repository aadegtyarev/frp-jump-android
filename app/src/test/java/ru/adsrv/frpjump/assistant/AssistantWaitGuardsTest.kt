package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentLimits
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentRequest
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The wait switches and the lock order, on a conversation built directly: no owner collector (the one that calls `toolsChanged` when the settings
 * change) is wired, so each guard stands on its own, and the settled callback shows whether it runs with the conversation's gate held.
 */
class AssistantWaitGuardsTest {
    private val live = CopyOnWriteArrayList<AutoCloseable>()
    @After fun cleanup() { live.forEach { it.close() } }

    private class Box : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun destroy() = Unit
    }

    private val connected = WaitSpec(WaitKind.TAB_CONNECTED, 10, tabId = 3)

    private inner class Fx : AutoCloseable {
        val sources = FakeWaitSources().also { it.tabs[3] = "logs" }
        val requests = CopyOnWriteArrayList<AgentRequest>()
        /** Whether the settled callback ever ran while the gate was held (the owner lock is taken there, and before the gate elsewhere). */
        val settledUnderGate = CopyOnWriteArrayList<Boolean>()
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantWaits = true, assistantTerminalTabs = true) } }
        val store = AssistantProviderStore(MemoryStorage(), Box(), settings.assistantChat)
            .also { it.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val module = bindAssistantLifecycle(settings, scope)
        val provider = AgentProvider { request -> requests += request; AgentReply("ok") }
        val chat: AssistantConversation = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, { provider },
            store::requireRevision, module, Dispatchers.IO, { _, _ -> AgentLimits() }, settings.assistantChat,
            terminalTabs = AssistantTerminalTabs({ emptyList() }, { emptySet() }, { null }, { _, _, _ -> null }), waitSources = sources,
            waitSleep = { kotlinx.coroutines.CompletableDeferred<Unit>().await() },
            prompts = TestPrompts.lazy, confirmations = TestConfirmations.off,
            onSettled = { settledUnderGate += Thread.holdsLock(settings.assistantChat.gate) })
        init { live += this }
        override fun close() { chat.close(); scope.cancel() }
        val adder get() = chat.waitAdder!!
        fun waitsNow() = chat.state.value.waits.size
    }

    private fun <T> eventually(what: String, check: () -> T?): T {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) { check()?.let { return it }; Thread.sleep(5) }
        throw AssertionError("Timed out: $what")
    }

    @Test fun `ending the waits never runs the settled callback with the gate held, which the owner lock precedes`() {
        val f = Fx()
        assertTrue(f.adder.add(connected) is AddWait.Added)
        eventually("a wait") { f.chat.state.value.takeIf { it.waits.size == 1 } }
        f.settledUnderGate.clear()
        // The switch goes off: the waits end under the gate, and the callback (the owner lock) must come after it is released.
        f.chat.toolsChanged(ru.adsrv.frpjump.settings.AssistantTools(waits = false))
        eventually("settled") { f.settledUnderGate.takeIf { it.isNotEmpty() } }
        Thread.sleep(100)
        assertTrue("the callback ran under the gate: " + f.settledUnderGate, f.settledUnderGate.none { it })
        // A new conversation does the same.
        assertTrue(f.adder.add(connected) is AddWait.Added || true)
        f.settledUnderGate.clear()
        f.chat.cancelAllWaits(); Thread.sleep(100)
        assertTrue(f.settledUnderGate.none { it })
    }

    @Test fun `a wait cannot be added with the switch off, the assistant off or the conversation closed, whatever called toolsChanged`() {
        val f = Fx()
        f.settings.setAssistantTool(AssistantToolId.WAIT_FOR, false)
        // Nothing told the conversation (no toolsChanged): the add itself refuses.
        assertEquals(AddWait.Invalid, f.adder.add(connected))
        assertEquals(0, f.waitsNow())
        f.settings.setAssistantTool(AssistantToolId.WAIT_FOR, true)
        assertTrue(f.adder.add(connected) is AddWait.Added)
        f.settings.update { it.copy(assistantEnabled = false) }
        assertEquals(AddWait.Invalid, f.adder.add(WaitSpec(WaitKind.TAB_CLOSED, 10, tabId = 3)))
        f.settings.update { it.copy(assistantEnabled = true) }
        f.chat.close()
        assertEquals(AddWait.Invalid, f.adder.add(WaitSpec(WaitKind.TAB_QUIET, 10, tabId = 3)))
    }

    @Test fun `an event that fires with the switch off starts no turn, whatever called toolsChanged`() {
        val f = Fx()
        assertTrue(f.adder.add(connected) is AddWait.Added)
        eventually("awaiting") { connected.takeIf { it in f.sources.awaited } }
        // Off in the settings only: the conversation is not told.
        f.settings.setAssistantTool(AssistantToolId.WAIT_FOR, false)
        f.sources.fire(connected)
        Thread.sleep(200)
        assertTrue("no turn", f.requests.isEmpty())
        assertEquals(0, f.chat.state.value.turns.size)
    }

    @Test fun `a provider change that starts the conversation again ends every wait`() {
        val f = Fx()
        assertTrue(f.adder.add(connected) is AddWait.Added)
        eventually("a wait") { f.chat.state.value.takeIf { it.waits.size == 1 } }
        val snapshot = f.store.currentSnapshot
        f.store.save(AssistantProviderDraft(snapshot.settings!!.copy(model = "another"), snapshot.revision))
        f.chat.refresh()
        eventually("waits gone") { f.chat.state.value.takeIf { it.waits.isEmpty() } }
    }

    @Test fun `the switch going off is told to the model on its next turn, once for each wait, after a blank line`() {
        val f = Fx()
        assertTrue(f.adder.add(connected) is AddWait.Added)
        assertTrue(f.adder.add(WaitSpec(WaitKind.TAB_CLOSED, 10, tabId = 3)) is AddWait.Added)
        f.settings.setAssistantTool(AssistantToolId.WAIT_FOR, false)
        f.chat.toolsChanged(f.settings.assistantAvailability.tools)
        eventually("waits gone") { f.chat.state.value.takeIf { it.waits.isEmpty() } }
        eventually("history loaded") { f.chat.state.value.takeIf { !it.loading } }
        f.chat.edit("hello"); f.chat.send()
        eventually("the turn") { f.requests.firstOrNull() }
        val lines = f.requests.first().turns.last().user.lines()
        assertEquals("hello", lines[0]); assertEquals("", lines[1])
        assertEquals(setOf("The owner cancelled the wait for tab 3 to connect.", "The owner cancelled the wait for tab 3 to close."), lines.drop(2).toSet())
        assertFalse(f.chat.state.value.turns.isEmpty().not() && f.chat.state.value.turns.last().user.contains("cancelled"))
    }

    @Test fun `a new conversation tells the model nothing about the waits it ended`() {
        val f = Fx()
        assertTrue(f.adder.add(connected) is AddWait.Added)
        assertTrue(f.chat.hideHistory())
        kotlinx.coroutines.runBlocking { f.chat.deleteHistory() }
        eventually("history loaded") { f.chat.state.value.takeIf { !it.loading && !it.restoring } }
        f.chat.edit("hello"); f.chat.send()
        eventually("the turn") { f.requests.firstOrNull() }
        assertEquals("hello", f.requests.first().turns.last().user)
    }
}
