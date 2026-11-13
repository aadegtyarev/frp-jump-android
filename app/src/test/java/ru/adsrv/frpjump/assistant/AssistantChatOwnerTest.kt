package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.ssh.SshExecInput
import ru.adsrv.frpjump.ops.ssh.SshExecOutput
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.ssh.*
import ru.adsrv.frpjump.store.SecretBox
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Р32: the app-lived owner of the chat conversation. The production owner, conversation, tool factory and kernel with a counting fake SSH
 * command owner; "the screen" is [AssistantChatOwner.attach] and [AssistantChatOwner.detach], exactly what the ViewModel does.
 */
class AssistantChatOwnerTest {
    private val live = CopyOnWriteArrayList<AutoCloseable>()
    @After fun cleanup() { live.forEach { it.close() } }

    private class Call(val command: String)
    private class Box : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun destroy() = Unit
    }

    private inner class Harness(private val calls: List<Call> = emptyList(), private val holdAt: Int = 0) : AutoCloseable {
        val executed = AtomicInteger()
        val requests = CopyOnWriteArrayList<AgentRequest>()
        @Volatile var now = 1_000L
        /** The provider's first reply waits here while [hold] is set. */
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val settings = SettingsStore(ru.adsrv.frpjump.ssh.MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantSshCommand = true) } }
        val store = AssistantProviderStore(ru.adsrv.frpjump.ssh.MemoryStorage(), Box(), settings.assistantChat)
            .also { it.save(AssistantProviderDraft(AssistantProviderSettings(model = "m"), null, "synthetic")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = AssistantChatHistory(ru.adsrv.frpjump.ssh.MemoryStorage(), Box(), scope, Dispatchers.Unconfined)
        private val synthetic = SyntheticProfileInformation()
        private val box = FakeKeystore().box("synthetic-ssh")
        private val env = SshEnvironment(SshKeyStore(ru.adsrv.frpjump.ssh.MemoryStorage(), box), SshPasswords(ru.adsrv.frpjump.ssh.MemoryStorage(), box), { "synthetic" },
            TmuxNames(ru.adsrv.frpjump.ssh.MemoryStorage()), clientFactory = { throw AssertionError("must not construct a client") })
        val ssh = object : AssistantSshCommands(SshTargets(synthetic.state, synthetic.hidden, env)) {
            override val exec = object : Operation<SshExecInput, SshExecOutput> {
                override val name = "ssh_exec"
                override val effect = Effect.CHANGE_REMOTE
                override fun target(input: SshExecInput) = GrantTarget(input.profile, input.connection)
                override suspend fun invoke(input: SshExecInput): OpResult<SshExecOutput> {
                    executed.incrementAndGet(); return OpResult.Done(SshExecOutput("ran ${input.command}", "", 0, null))
                }
            }
            override fun stamp(target: GrantTarget): Any? = "stamp-1"
        }
        val module = bindAssistantLifecycle(settings, scope)
        val owner = AssistantChatOwner(scope, settings, store, module, null, ssh, history, null, TestPrompts.placeholder, {
            AgentProvider { request ->
                requests += request
                if (requests.size == holdAt) { entered.complete(Unit); release.await() }
                val done = request.turns.last().exchanges.size
                if (done < calls.size) AgentReply(calls = listOf(ToolCall("c$done", "ssh_command", buildJsonObject {
                    put("profile_id", "p"); put("connection_id", "c"); put("command", calls[done].command)
                }))) else AgentReply("done")
            }
        }, { now })
        init { live += this }
        override fun close() { owner.shutdown(); scope.cancel() }
        fun results() = requests.last().turns.last().exchanges.flatMap { it.results }
    }

    private fun <T> eventually(what: String, check: () -> T?): T {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) { check()?.let { return it }; Thread.sleep(5) }
        throw AssertionError("Timed out: $what")
    }
    private fun AssistantConversation.card() = eventually("card") { state.value.confirmation }
    private fun Harness.idle() { eventually("idle") { owner.activity.value.takeIf { it == ChatActivity.IDLE } } }
    private fun Harness.grantKey() = GrantKey(AssistantToolId.SSH_COMMAND, GrantTarget("p", "c"))
    private fun Harness.send(chat: AssistantConversation, text: String = "go") {
        eventually("history loaded") { chat.state.value.takeIf { !it.loading } }
        chat.edit(text); chat.send()
    }

    @Test fun `a Send outlives its screen and the reply is in the saved history on return`() {
        val h = Harness(holdAt = 1)
        val first = h.owner.attach(); h.send(first); runBlocking { withTimeout(5000) { h.entered.await() } }
        h.owner.detach()
        assertEquals(ChatActivity.WORKING, h.owner.activity.value)
        h.release.complete(Unit); h.idle()
        val again = h.owner.attach()
        assertNotSame("the first conversation closed once the attempt ended with no screen", first, again)
        val turn = eventually("restored") { again.state.value.turns.singleOrNull() }
        assertEquals("go", turn.user); assertEquals("done", turn.answer)
        assertEquals("done", runBlocking { h.history.load()!!.records.single().exchanges.single().reply.text })
    }

    @Test fun `reopening while the attempt runs gets the same conversation and Stop stops that attempt`() {
        val h = Harness(listOf(Call("uptime")), holdAt = 1)
        val first = h.owner.attach(); h.send(first); runBlocking { withTimeout(5000) { h.entered.await() } }
        h.owner.detach()
        val again = h.owner.attach()
        assertSame(first, again); assertTrue(again.state.value.busy)
        again.stop(); h.idle()
        assertEquals(0, h.executed.get())
        h.owner.detach()
        val restored = h.owner.attach()
        eventually("interrupted turn restored") { restored.state.value.turns.singleOrNull()?.takeIf { it.interrupted } }
    }

    @Test fun `the conversation closes only when no screen and no attempt remain, and then the grants go`() {
        val h = Harness(listOf(Call("uptime")))
        val first = h.owner.attach(); h.send(first)
        val support = h.owner.confirmations!!
        first.answer(first.card().id, ConfirmationDecision.SESSION); h.idle()
        assertTrue("the attempt ended, the screen stays: the session lasts", support.grants.has(h.grantKey(), "stamp-1"))
        assertSame(first, h.owner.attach())
        h.owner.detach()
        assertTrue("one screen is still attached", support.grants.has(h.grantKey(), "stamp-1"))
        h.owner.detach()
        assertFalse("screen gone and no attempt: the session ends", support.grants.has(h.grantKey(), "stamp-1"))
        assertNull(h.owner.confirmations)
        assertNotSame(first, h.owner.attach())
    }

    @Test fun `a session grant survives leaving while the attempt runs and ends with it`() {
        val h = Harness(listOf(Call("a"), Call("b")), holdAt = 2)
        val chat = h.owner.attach(); h.send(chat)
        val support = h.owner.confirmations!!
        chat.answer(chat.card().id, ConfirmationDecision.SESSION)
        runBlocking { withTimeout(5000) { h.entered.await() } }
        h.owner.detach()
        assertTrue("no screen, the attempt still runs: the grant stays", support.grants.has(h.grantKey(), "stamp-1"))
        h.release.complete(Unit); h.idle()
        assertEquals("the grant covered the second call: only one question", 2, h.executed.get())
        assertFalse("both ended: the grant is cleared", support.grants.has(h.grantKey(), "stamp-1"))
    }

    @Test fun `a card raised after leaving waits, is shown on return and can be answered`() {
        val h = Harness(listOf(Call("uptime")), holdAt = 1)
        val chat = h.owner.attach(); h.send(chat); runBlocking { withTimeout(5000) { h.entered.await() } }
        h.owner.detach()
        h.release.complete(Unit)
        eventually("waiting") { h.owner.activity.value.takeIf { it == ChatActivity.WAITING } }
        Thread.sleep(100); assertEquals(0, h.executed.get())
        val back = h.owner.attach(); assertSame(chat, back)
        val card = back.state.value.confirmation!!
        back.answer(card.id, ConfirmationDecision.ONCE); h.idle()
        assertEquals(1, h.executed.get())
    }

    @Test fun `an answer after five real minutes is ignored and nothing runs`() {
        val h = Harness(listOf(Call("uptime")))
        val chat = h.owner.attach(); h.send(chat)
        val card = chat.card()
        h.now += 300_000
        chat.answer(card.id, ConfirmationDecision.ONCE); h.idle()
        assertEquals(0, h.executed.get())
        val result = h.results().single()
        assertEquals(ToolOutcome.DECLINED, result.outcome); assertEquals(ConfirmationTexts.NO_ANSWER, result.text)
    }

    @Test fun `just under five real minutes the answer still runs`() {
        val h = Harness(listOf(Call("uptime")))
        val chat = h.owner.attach(); h.send(chat)
        val card = chat.card()
        h.now += 299_999
        chat.answer(card.id, ConfirmationDecision.ONCE); h.idle()
        assertEquals(1, h.executed.get())
    }

    @Test fun `a card that ran out while no screen was shown is no answer on return`() {
        val h = Harness(listOf(Call("uptime")))
        val chat = h.owner.attach(); h.send(chat)
        val card = chat.card()
        h.owner.detach()
        h.now += 300_000
        val back = h.owner.attach(); assertSame(chat, back)
        h.idle()
        assertNull(back.state.value.confirmation)
        back.answer(card.id, ConfirmationDecision.ONCE)
        assertEquals("no press runs the call", 0, h.executed.get())
        val result = h.results().single()
        assertEquals(ToolOutcome.DECLINED, result.outcome); assertEquals(ConfirmationTexts.NO_ANSWER, result.text)
    }

    @Test fun `disable acts with no screen, stops the attempt and nothing runs`() {
        val h = Harness(listOf(Call("uptime")))
        val chat = h.owner.attach(); h.send(chat); chat.card()
        h.owner.detach()
        h.settings.update { it.copy(assistantEnabled = false) }
        h.idle()
        assertEquals(0, h.executed.get()); assertNull(h.owner.confirmations)
    }

    @Test fun `a provider change acts with no screen, stops the attempt and nothing runs`() {
        val h = Harness(listOf(Call("uptime")))
        val chat = h.owner.attach(); h.send(chat); chat.card()
        h.owner.detach()
        h.store.save(AssistantProviderDraft(AssistantProviderSettings(model = "new"), h.store.state.value.revision))
        h.idle()
        assertEquals(0, h.executed.get())
    }

    @Test fun `a tool switched off clears its grants with no screen`() {
        val h = Harness(listOf(Call("a"), Call("b")), holdAt = 2)
        val chat = h.owner.attach(); h.send(chat)
        val support = h.owner.confirmations!!
        chat.answer(chat.card().id, ConfirmationDecision.SESSION)
        runBlocking { withTimeout(5000) { h.entered.await() } }
        h.owner.detach()
        assertTrue(support.grants.has(h.grantKey(), "stamp-1"))
        h.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, false)
        eventually("grants cleared") { true.takeIf { !support.grants.has(h.grantKey(), "stamp-1") } }
        // The second call, which the grant would have covered, now asks.
        h.release.complete(Unit)
        assertEquals("b", chat.card().command)
        assertEquals(1, h.executed.get())
        chat.stop(); h.idle()
    }

    @Test fun `the profile's removal stops the attempt through the owner and nothing runs`() {
        val h = Harness(listOf(Call("uptime")))
        val chat = h.owner.attach(); h.send(chat); chat.card()
        h.owner.detach()
        h.owner.stop()
        h.idle()
        assertEquals(0, h.executed.get())
    }

    @Test fun `the indicator follows working, waiting and the end`() {
        val h = Harness(listOf(Call("uptime")), holdAt = 1)
        assertEquals(ChatActivity.IDLE, h.owner.activity.value)
        val chat = h.owner.attach(); h.send(chat); runBlocking { withTimeout(5000) { h.entered.await() } }
        eventually("working") { h.owner.activity.value.takeIf { it == ChatActivity.WORKING } }
        h.release.complete(Unit)
        val card = chat.card()
        eventually("waiting") { h.owner.activity.value.takeIf { it == ChatActivity.WAITING } }
        chat.answer(card.id, ConfirmationDecision.ONCE)
        eventually("idle after the end") { chat.state.value.takeIf { !it.busy } }
        h.owner.detach(); h.idle()
    }

    @Test fun `the draft is saved when the screen is left`() {
        val h = Harness()
        val chat = h.owner.attach()
        eventually("history loaded") { chat.state.value.takeIf { !it.loading } }
        chat.edit("a half written question")
        h.owner.detach()
        val back = h.owner.attach()
        assertNotSame(chat, back)
        eventually("draft restored") { back.state.value.draft.takeIf { it == "a half written question" } }
    }

    @Test fun `no conversation is built before the first attach`() {
        val h = Harness()
        assertNull(h.owner.confirmations); assertEquals(ChatActivity.IDLE, h.owner.activity.value)
        h.owner.stop(); h.owner.detach()
        assertNull(h.owner.confirmations)
    }
}
