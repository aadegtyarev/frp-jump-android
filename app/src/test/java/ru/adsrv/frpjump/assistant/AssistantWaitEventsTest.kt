package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentRequest
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.assistant.chat.ChatState
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Event turns of the production owner, conversation, tool factory and kernel with fake wait sources: an event starts a turn by itself, queues while one
 * runs, stops at the owner's cap, and ends with a new conversation, the switch off, the assistant off or the owner's cancel, but not with Stop.
 */
class AssistantWaitEventsTest {
    private val live = CopyOnWriteArrayList<AutoCloseable>()
    @After fun cleanup() { live.forEach { it.close() } }

    private class Box : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun destroy() = Unit
    }

    private val connected = WaitSpec(WaitKind.TAB_CONNECTED, 10, tabId = 3)
    private val closed = WaitSpec(WaitKind.TAB_CLOSED, 10, tabId = 3)
    private val quiet = WaitSpec(WaitKind.TAB_QUIET, 10, tabId = 3)

    private inner class Harness(limits: AssistantLimits = AssistantLimits(), private val arm: List<WaitSpec> = listOf(connected)) : AutoCloseable {
        val sources = FakeWaitSources().also { it.tabs[3] = "logs" }
        val requests = CopyOnWriteArrayList<AgentRequest>()
        /** A turn whose user text is "slow" waits here. */
        val slow = CompletableDeferred<Unit>()
        val settings = SettingsStore(MemoryStorage()).apply {
            update { it.copy(assistantEnabled = true, assistantWaits = true, assistantTerminalTabs = true, assistantLimits = limits) }
        }
        val store = ru.adsrv.frpjump.assistant.AssistantProviderStore(MemoryStorage(), Box(), settings.assistantChat)
            .also { it.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val storage = ChatStorage()
        val history = AssistantChatHistory(storage, Box(), scope, Dispatchers.Unconfined)
        val module = bindAssistantLifecycle(settings, scope)
        val owner = AssistantChatOwner(scope, settings, store, module, null, null, history, null, TestPrompts.placeholder, {
            AgentProvider { request ->
                requests += request
                val last = request.turns.last()
                if (last.user.startsWith("slow")) slow.await()
                if (last.user.startsWith("arm") && last.exchanges.isEmpty()) {
                    AgentReply(calls = arm.mapIndexed { i, spec -> ToolCall("w$i", "wait_for", buildJsonObject {
                        put("event", spec.kind.wire); put("tab_id", spec.tabId ?: 0); put("minutes", spec.minutes)
                    }) })
                } else AgentReply("ok")
            }
        }, { 1_000L }, terminalTabs = AssistantTerminalTabs({ emptyList() }, { emptySet() }, { null }, { _, _, _ -> null }), waitSources = sources, waitSleep = { CompletableDeferred<Unit>().await() })
        val chat = owner.attach()
        init { live += this; eventually("history loaded") { chat.state.value.takeIf { !it.loading } } }
        override fun close() { owner.shutdown(); scope.cancel() }

        fun say(text: String) { chat.edit(text); chat.send() }
        fun idle() = eventually("idle") { chat.state.value.takeIf { !it.busy } }
        /** The owner arms the waits and the turn is over. */
        fun armed(count: Int = arm.size) { say("arm"); eventually("waits") { chat.state.value.takeIf { it.waits.size == count && !it.busy } } }
        fun userTexts() = requests.map { it.turns.last().user }
        fun fire(spec: WaitSpec) { eventually("awaiting $spec") { spec in sources.awaited }; sources.fire(spec) }
        fun eventTurns() = chat.state.value.turns.filter { it.eventOnly }
    }

    private class ChatStorage : ru.adsrv.frpjump.store.TextStorage {
        val inner = MemoryStorage()
        override fun read() = inner.read()
        override fun write(text: String) = inner.write(text)
        override fun delete() = inner.delete()
    }

    private fun <T> eventually(what: String, seconds: Long = 5, check: () -> T?): T {
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < until) { check()?.let { return it }; Thread.sleep(5) }
        throw AssertionError("Timed out: $what")
    }

    @Test fun `an event starts a turn by itself, marked as the app's and not the owner's`() {
        val h = Harness()
        h.armed()
        val wait = h.chat.state.value.waits.single()
        assertEquals("logs", wait.subject)
        h.fire(connected)
        eventually("event turn") { h.eventTurns().takeIf { it.isNotEmpty() && !h.chat.state.value.busy } }
        assertEquals("Event: tab 3 connected.", h.userTexts().last())
        val turn = h.eventTurns().single()
        assertEquals(listOf(WaitEvent(WaitKind.TAB_CONNECTED, 3, "logs")), turn.events)
        assertEquals("ok", turn.answer)
        assertTrue(h.chat.state.value.waits.isEmpty())
        // The saved turn says where it came from, so the history never reads it as the owner's words.
        eventually("saved") { h.storage.inner.text?.takeIf { it.contains("\"eventOnly\":true") } }
    }

    @Test fun `events that fire while a turn runs wait for it, and several go in one turn`() {
        val h = Harness(arm = listOf(connected, closed))
        h.armed(2)
        h.say("slow")
        eventually("slow turn running") { h.requests.lastOrNull()?.turns?.last()?.user == "slow" }
        h.fire(connected); h.fire(closed)
        Thread.sleep(150)
        assertEquals("no turn starts while one runs", listOf("arm", "arm", "slow"), h.userTexts())
        h.slow.complete(Unit)
        eventually("one event turn with both") { h.eventTurns().singleOrNull() }
        assertEquals(setOf("Event: tab 3 connected.", "Event: tab 3 closed."), h.userTexts().last().lines().toSet())
        assertEquals(2, h.eventTurns().single().events.size)
    }

    @Test fun `event replies in a row stop at the owner's limit, with the line, and the owner's message sends the rest and starts the count again`() {
        val h = Harness(AssistantLimits(eventReplies = 2), arm = listOf(connected, closed, quiet))
        h.armed(3)
        h.fire(connected); eventually("first") { (h.eventTurns().size == 1 && !h.chat.state.value.busy).takeIf { it } }
        h.fire(closed); eventually("second") { (h.eventTurns().size == 2 && !h.chat.state.value.busy).takeIf { it } }
        h.fire(quiet)
        eventually("the cap line") { h.chat.state.value.eventCap }
        assertEquals(2, h.chat.state.value.eventCap)
        Thread.sleep(150)
        assertEquals("no third turn", 2, h.eventTurns().size)
        // The owner writes: what waited goes with the message, the cap line goes, the count starts again.
        h.say("hi")
        eventually("owner turn") { h.chat.state.value.takeIf { it.turns.size == 4 && !it.busy } }
        val text = h.userTexts().last()
        assertEquals(listOf("hi", "", "Event: tab 3 is quiet."), text.lines())
        assertNull(h.chat.state.value.eventCap)
        val owners = h.chat.state.value.turns.last()
        assertFalse(owners.eventOnly); assertEquals("hi", owners.user); assertEquals(1, owners.events.size)
    }

    @Test fun `the switch off ends every wait and nothing fired starts a turn`() {
        val h = Harness()
        h.armed()
        h.settings.setAssistantTool(AssistantToolId.WAIT_FOR, false)
        eventually("waits gone") { h.chat.state.value.takeIf { it.waits.isEmpty() } }
        h.sources.fire(connected); Thread.sleep(150)
        assertEquals(listOf("arm", "arm"), h.userTexts())
        assertTrue(h.eventTurns().isEmpty())
    }

    @Test fun `the assistant off ends every wait`() {
        val h = Harness()
        h.armed()
        h.settings.update { it.copy(assistantEnabled = false) }
        eventually("waits gone") { h.chat.state.value.takeIf { it.waits.isEmpty() } }
    }

    @Test fun `a new conversation ends every wait`() {
        val h = Harness()
        h.armed()
        assertTrue(h.chat.hideHistory())
        runBlocking { h.chat.deleteHistory() }
        eventually("waits gone") { h.chat.state.value.takeIf { it.waits.isEmpty() } }
        h.sources.fire(connected); Thread.sleep(150)
        assertTrue(h.eventTurns().isEmpty())
    }

    @Test fun `Stop stops only the reply, the waits stay and the next event still starts a turn`() {
        val h = Harness()
        h.armed()
        h.say("slow")
        eventually("running") { (h.chat.state.value.busy && h.requests.last().turns.last().user == "slow").takeIf { it } }
        h.chat.stop()
        h.slow.complete(Unit)
        eventually("stopped") { h.chat.state.value.takeIf { !it.busy } }
        assertEquals(1, h.chat.state.value.waits.size)
        h.fire(connected)
        eventually("event turn") { h.eventTurns().singleOrNull() }
        assertEquals("Event: tab 3 connected.", h.userTexts().last())
    }

    @Test fun `the owner cancelling one wait or all starts no turn and the model is told on its next turn`() {
        val h = Harness(arm = listOf(connected, closed))
        h.armed(2)
        val first = h.chat.state.value.waits.first { it.spec == connected }
        h.chat.cancelWait(first.id)
        eventually("one left") { h.chat.state.value.takeIf { it.waits.size == 1 } }
        h.chat.cancelAllWaits()
        eventually("none left") { h.chat.state.value.takeIf { it.waits.isEmpty() } }
        h.sources.fire(connected); h.sources.fire(closed); Thread.sleep(150)
        assertEquals("no turn starts", listOf("arm", "arm"), h.userTexts())
        h.say("go")
        eventually("owner turn") { h.chat.state.value.takeIf { it.turns.size == 2 && !it.busy } }
        assertEquals(listOf("go", "", "The owner cancelled the wait for tab 3 to connect.", "The owner cancelled the wait for tab 3 to close."), h.userTexts().last().lines())
        // Notes are for the model: the owner's bubble is still just their words.
        assertEquals("go", h.chat.state.value.turns.last().user)
    }

    @Test fun `a wait keeps the conversation alive when its screen goes, and an ended one lets it close`() {
        val h = Harness()
        h.armed()
        h.owner.detach()
        assertSame(h.chat, h.owner.attach())
        h.owner.detach()
        h.chat.cancelAllWaits()
        eventually("closed when idle") { h.owner.attach().takeIf { it !== h.chat } }
    }

    @Test fun `the waiting count follows the waits for the notification`() {
        val h = Harness(arm = listOf(connected, closed))
        h.armed(2)
        eventually("count 2") { h.owner.waitCount.value.takeIf { it == 2 } }
        eventually("listening") { h.owner.activity.value.takeIf { it == ChatActivity.LISTENING } }
        h.chat.cancelAllWaits()
        eventually("idle") { h.owner.activity.value.takeIf { it == ChatActivity.IDLE } }
        assertEquals(0, h.owner.waitCount.value)
    }
}
