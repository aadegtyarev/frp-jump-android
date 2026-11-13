package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.ssh.SshExecInput
import ru.adsrv.frpjump.ops.ssh.SshExecOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.ssh.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The production conversation, tool factory and kernel with a counting fake SSH command owner; nothing here opens a real client. */
class AssistantConfirmationFlowTest {
    private val saved = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "r")
    private val live = CopyOnWriteArrayList<AutoCloseable>()
    @After fun cleanup() { live.forEach { it.close() } }

    private class Call(val profile: String, val connection: String, val command: String)

    private inner class Harness(private val calls: List<Call>, dispatcher: CoroutineDispatcher? = null, clock: (() -> Long)? = null,
        persistFails: Boolean = false, realtime: (() -> Long)? = null, enabledAtStart: Boolean = true, private val providerDelayMs: Long = 0, modelTimeoutMs: Long = 60_000,
        private val custom: ((AgentRequest) -> AgentReply?)? = null, withSupport: Boolean = true) : AutoCloseable {
        @Volatile var snapshot = saved
        val executed = AtomicInteger()
        val commands = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<AgentRequest>()
        @Volatile var stamp: Any? = "stamp-1"
        @Volatile var persistFails = persistFails
        val file = ru.adsrv.frpjump.ssh.MemoryStorage()
        val settings = SettingsStore(file).apply {
            update { it.copy(assistantEnabled = enabledAtStart, assistantSshCommand = true) }
        }
        private val synthetic = SyntheticProfileInformation()
        private val box = FakeKeystore().box("synthetic-ssh")
        private val env = SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { throw AssertionError("must not construct a client") })
        val ssh = object : AssistantSshCommands(SshTargets(synthetic.state, synthetic.hidden, env)) {
            override val exec = object : Operation<SshExecInput, SshExecOutput> {
                override val name = "ssh_exec"
                override val effect = Effect.CHANGE_REMOTE
                override fun target(input: SshExecInput) = GrantTarget(input.profile, input.connection)
                override suspend fun invoke(input: SshExecInput): OpResult<SshExecOutput> {
                    executed.incrementAndGet(); commands += input.command; return OpResult.Done(SshExecOutput("ran ${input.command}", "", 0, null))
                }
            }
            override fun stamp(target: GrantTarget): Any? = this@Harness.stamp
        }
        /** The production wiring (policy, commit of "off", names, stamp, tool switch); only the persistence failure is simulated. */
        val support = chatConfirmationSupport(settings, ssh).let { real ->
            ConfirmationSupport(real.policy, { if (this.persistFails) false else real.persistOff() }, real.info, { ssh.stamp(it) }, real.grants, real.toolEnabled)
        }
        val scope = CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Default))
        val chat: AssistantConversation
        init {
            chat = AssistantConversation(scope, { settings.assistantAvailability }, { snapshot }, {
                AgentProvider { request ->
                    if (providerDelayMs > 0) delay(providerDelayMs)
                    requests += request
                    custom?.invoke(request)?.let { return@AgentProvider it }
                    val done = request.turns.last().exchanges.size
                    if (done < calls.size) AgentReply(calls = listOf(ToolCall("c$done", "ssh_command", buildJsonObject {
                        put("profile_id", calls[done].profile); put("connection_id", calls[done].connection); put("command", calls[done].command)
                    }))) else AgentReply("done")
                }
            }, {}, AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled }),
                io = dispatcher ?: Dispatchers.Default, limits = { AgentLimits(modelTimeoutMs = modelTimeoutMs) }, runtime = settings.assistantChat, sshCommands = ssh, prompts = TestPrompts.placeholder,
                confirmations = if (withSupport) support else null, activeClock = clock ?: { System.nanoTime() / 1_000_000 }, realtime = realtime ?: clock ?: { System.nanoTime() / 1_000_000 })
            live += this
        }
        val state get() = chat.state.value
        fun send(text: String = "go") { chat.edit(text); chat.send() }
        fun results() = requests.last().turns.last().exchanges.flatMap { it.results }
        override fun close() { chat.close(); scope.cancel() }
    }

    private fun <T> eventually(what: String, check: () -> T?): T {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) { check()?.let { return it }; Thread.sleep(5) }
        throw AssertionError("Timed out: $what")
    }
    private fun Harness.card() = eventually("card") { state.confirmation }
    private fun Harness.idle() { eventually("idle") { state.busy.takeIf { !it } } }
    /** Bounded virtual wait: a regression fails the assertions after it instead of looping forever. */
    private suspend fun until(limit: Int = 20_000, condition: () -> Boolean) { var n = 0; while (!condition() && n++ < limit) delay(100) }
    private fun one(command: String = "uptime", profile: String = "p", connection: String = "c") = listOf(Call(profile, connection, command))

    @Test fun `a changing call asks first and nothing runs before the answer`() {
        val h = Harness(one())
        h.send()
        val card = h.card()
        Thread.sleep(150)
        assertEquals(0, h.executed.get()); assertEquals(1, h.requests.size)
        assertEquals("uptime", card.command); assertEquals(GrantTarget("p", "c"), card.target)
        assertFalse("the pending question never prints its command", card.toString().contains("uptime"))
        h.chat.answer(card.id, ConfirmationDecision.ONCE); h.idle()
        assertEquals(1, h.executed.get()); assertEquals(ToolOutcome.SUCCESS, h.results().single().outcome)
        assertNull(h.state.confirmation)
    }

    @Test fun `Allow once asks again for the next call`() {
        val h = Harness(listOf(Call("p", "c", "a"), Call("p", "c", "b")))
        h.send(); h.chat.answer(h.card().id, ConfirmationDecision.ONCE)
        val second = eventually("second card") { h.state.confirmation?.takeIf { it.command == "b" } }
        assertEquals(1, h.executed.get())
        h.chat.answer(second.id, ConfirmationDecision.ONCE); h.idle()
        assertEquals(listOf("a", "b"), h.commands)
    }

    @Test fun `Decline executes nothing and gives the fixed text as a DECLINED result and no failure`() {
        val h = Harness(one())
        h.send(); h.chat.answer(h.card().id, ConfirmationDecision.DENY); h.idle()
        assertEquals(0, h.executed.get())
        val result = h.results().single()
        assertEquals(ToolOutcome.DECLINED, result.outcome); assertEquals(ConfirmationTexts.DECLINED, result.text)
        val turn = h.state.turns.single()
        assertFalse(turn.toolFailed); assertTrue(turn.toolFailures.isEmpty())
        assertEquals(ToolOutcome.DECLINED, turn.receipts.single().result?.outcome)
        assertNull(h.state.toolProgress); assertEquals("done", turn.answer)
    }

    @Test fun `For session skips the same target only and not other connections`() {
        val h = Harness(listOf(Call("p", "c", "a"), Call("p", "c", "b"), Call("p", "other", "c"), Call("q", "c", "d")))
        h.send(); h.chat.answer(h.card().id, ConfirmationDecision.SESSION)
        val other = eventually("other connection asks") { h.state.confirmation?.takeIf { it.command == "c" } }
        assertEquals("the same target did not ask again", listOf("a", "b"), h.commands)
        h.chat.answer(other.id, ConfirmationDecision.ONCE)
        val profile = eventually("other profile asks") { h.state.confirmation?.takeIf { it.command == "d" } }
        h.chat.answer(profile.id, ConfirmationDecision.DENY); h.idle()
        assertEquals(listOf("a", "b", "c"), h.commands)
    }

    private fun granted(): Harness {
        val h = Harness(one("first"))
        h.send(); h.chat.answer(h.card().id, ConfirmationDecision.SESSION); h.idle()
        assertTrue(h.support.grants.has(GrantKey(AssistantToolId.SSH_COMMAND, GrantTarget("p", "c")), "stamp-1"))
        return h
    }
    private fun Harness.asksAgain(): Boolean {
        requests.clear()
        val before = executed.get()
        send("again")
        val asked = try { eventually("asked") { state.confirmation } ; true } catch (_: AssertionError) { false }
        if (asked) { chat.answer(state.confirmation!!.id, ConfirmationDecision.DENY); idle() } else idle()
        return asked && executed.get() == before
    }

    @Test fun `a session grant is kept while the target and tool stay unchanged`() {
        val h = granted()
        h.requests.clear()
        h.send("again"); h.idle()
        assertEquals(2, h.executed.get())
    }

    @Test fun `a session grant does not follow an edited or removed target`() {
        val edited = granted()
        edited.stamp = "stamp-2"
        assertTrue(edited.asksAgain())
        val removed = granted()
        removed.stamp = null
        assertTrue(removed.asksAgain())
    }

    @Test fun `closing the conversation switching the tool off history clear and assistant disable end session grants`() {
        val closed = granted(); closed.chat.close()
        assertFalse(closed.support.grants.has(GrantKey(AssistantToolId.SSH_COMMAND, GrantTarget("p", "c")), "stamp-1"))
        val off = granted()
        off.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, false)
        off.chat.toolsChanged(off.settings.assistantAvailability.tools)
        off.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true)
        assertTrue(off.asksAgain())
        val cleared = granted()
        runBlocking { cleared.chat.deleteHistory() }
        assertTrue(cleared.asksAgain())
        val disabled = granted()
        disabled.settings.update { it.copy(assistantEnabled = false) }; disabled.chat.refresh()
        disabled.settings.update { it.copy(assistantEnabled = true) }; disabled.chat.refresh()
        assertTrue(disabled.asksAgain())
    }

    @Test fun `Always persists off first and later calls in the same Send do not ask`() {
        val h = Harness(listOf(Call("p", "c", "a"), Call("p", "c", "b")))
        h.send(); h.chat.answer(h.card().id, ConfirmationDecision.ALWAYS); h.idle()
        assertEquals(ConfirmationMode.OFF, h.settings.assistantConfirmations.global)
        assertEquals(listOf("a", "b"), h.commands)
        assertEquals(ConfirmationMode.OFF, SettingsStore(MemoryStorage(h.file.text)).assistantConfirmations.global)
    }

    @Test fun `Always that cannot be saved allows this call once and keeps asking`() {
        val h = Harness(listOf(Call("p", "c", "a"), Call("p", "c", "b")), persistFails = true)
        h.send(); h.chat.answer(h.card().id, ConfirmationDecision.ALWAYS)
        val second = eventually("asks again") { h.state.confirmation?.takeIf { it.command == "b" } }
        assertEquals(listOf("a"), h.commands); assertEquals(ConfirmationMode.ASK, h.settings.assistantConfirmations.global)
        assertTrue(h.state.confirmationNotSaved)
        h.chat.answer(second.id, ConfirmationDecision.DENY); h.idle()
    }

    @Test fun `requests off means no question and the policy is read live between calls`() {
        val off = Harness(one())
        off.settings.setAssistantConfirmations(ConfirmationMode.OFF)
        off.send(); off.idle()
        assertEquals(1, off.executed.get())
        val h = Harness(listOf(Call("p", "c", "a"), Call("p", "c", "b"), Call("p", "c", "c")))
        h.send(); h.chat.answer(h.card().id, ConfirmationDecision.ONCE)
        // The policy changes while the Send runs: the very next call must follow the committed value, not the one at Send start.
        h.settings.setAssistantConfirmations(ConfirmationMode.OFF)
        h.idle()
        assertEquals(listOf("a", "b", "c"), h.commands)
        assertNull(h.state.confirmation)
    }

    @Test fun `Stop during the wait runs nothing clears the card and frees the slot`() {
        val h = Harness(one())
        h.send(); val card = h.card()
        h.chat.stop(); h.idle()
        assertEquals(0, h.executed.get()); assertNull(h.state.confirmation)
        eventually("slot free") { h.settings.assistantChat.busy.value.takeIf { !it } }
        h.chat.answer(card.id, ConfirmationDecision.ONCE)
        Thread.sleep(100); assertEquals(0, h.executed.get())
    }

    @Test fun `assistant disable and a forced close during the wait run nothing and clear the card`() {
        val disabled = Harness(one())
        disabled.send(); disabled.card()
        disabled.settings.update { it.copy(assistantEnabled = false) }; disabled.chat.refresh()
        disabled.idle()
        assertEquals(0, disabled.executed.get()); assertNull(disabled.state.confirmation)
        val closed = Harness(one())
        closed.send(); val card = closed.card()
        closed.chat.close()
        eventually("slot free") { closed.settings.assistantChat.busy.value.takeIf { !it } }
        assertEquals(0, closed.executed.get()); closed.chat.answer(card.id, ConfirmationDecision.ONCE)
        assertNull(closed.state.confirmation)
    }

    @Test fun `stale unknown and repeated answers are ignored`() {
        val h = Harness(listOf(Call("p", "c", "a"), Call("p", "c", "b")))
        h.chat.answer(1, ConfirmationDecision.ONCE)
        h.send(); val first = h.card()
        h.chat.answer(first.id + 100, ConfirmationDecision.ONCE); h.chat.answer(first.id, ConfirmationDecision.NO_ANSWER)
        Thread.sleep(100); assertEquals(0, h.executed.get()); assertNotNull(h.state.confirmation)
        h.chat.answer(first.id, ConfirmationDecision.ONCE); h.chat.answer(first.id, ConfirmationDecision.ONCE)
        val second = eventually("second card") { h.state.confirmation?.takeIf { it.id != first.id } }
        h.chat.answer(first.id, ConfirmationDecision.ONCE)
        Thread.sleep(100); assertEquals("a stale id never answers a newer question", listOf("a"), h.commands)
        h.chat.answer(second.id, ConfirmationDecision.DENY); h.idle()
    }

    @Test fun `five minutes without an answer is a DECLINED result and the Send keeps its active time`() {
        val vt = VirtualTime()
        val h = Harness(one(), dispatcher = vt, clock = { vt.now })
        val waited = vt.run {
            h.send()
            until { h.state.confirmation != null }
            val shownAt = vt.now
            until { !h.state.busy }
            vt.now - shownAt
        }
        assertTrue("waited the full five minutes: $waited", waited >= 300_000)
        assertEquals(0, h.executed.get())
        val result = h.results().single()
        assertEquals(ToolOutcome.DECLINED, result.outcome); assertEquals(ConfirmationTexts.NO_ANSWER, result.text)
        assertNull("the wait did not time the Send out", h.state.problem); assertEquals("done", h.state.turns.single().answer)
        assertFalse("after the timeout the question is gone and the history is shown again", h.state.asking)
    }

    @Test fun `the wait ends at five real minutes even when the coroutine timer has not counted them (device sleep)`() {
        val vt = VirtualTime(); var slept = 0L
        val h = Harness(one(), dispatcher = vt, clock = { vt.now }, realtime = { vt.now + slept })
        val endedAt = vt.run {
            h.send()
            until { h.state.confirmation != null }
            slept = 300_000
            until { !h.state.busy }
            vt.now
        }
        assertTrue("ended on a short re-check, not after five minutes of timer time: $endedAt", endedAt < 60_000)
        assertEquals(0, h.executed.get())
        val result = h.results().single()
        assertEquals(ToolOutcome.DECLINED, result.outcome); assertEquals(ConfirmationTexts.NO_ANSWER, result.text)
    }

    @Test fun `a long wait for the owner does not use up the 120 second Send limit`() {
        val vt = VirtualTime()
        val h = Harness(one(), dispatcher = vt, clock = { vt.now }, providerDelayMs = 50_000)
        vt.run {
            h.send()
            until { h.state.confirmation != null }
            delay(200_000)
            h.chat.answer(h.state.confirmation!!.id, ConfirmationDecision.ONCE)
            until { !h.state.busy }
        }
        assertNull("100 s of model time and 200 s of waiting is within the limit", h.state.problem)
        assertEquals(1, h.executed.get()); assertEquals("done", h.state.turns.single().answer)
        assertTrue(vt.now >= 300_000)
    }

    @Test fun `active time still ends the Send after 120 seconds`() {
        val vt = VirtualTime()
        val h = Harness(one(), dispatcher = vt, clock = { vt.now }, providerDelayMs = 130_000, modelTimeoutMs = 600_000)
        val problem = vt.run {
            h.settings.setAssistantConfirmations(ConfirmationMode.OFF)
            h.send()
            until { !h.state.busy }
            h.state.problem
        }
        assertEquals(ChatProblem.TIMEOUT, problem); assertEquals(0, h.executed.get())
    }

    @Test fun `an answer from the card of the previous Send cannot answer a later one`() {
        val h = Harness(one("x"))
        h.send(); val old = h.card(); h.chat.answer(old.id, ConfirmationDecision.DENY); h.idle()
        h.requests.clear()
        h.send("again"); val next = h.card()
        assertNotEquals(old.id, next.id)
        h.chat.answer(old.id, ConfirmationDecision.ONCE)
        Thread.sleep(100); assertEquals(0, h.executed.get()); assertNotNull(h.state.confirmation)
        h.chat.answer(next.id, ConfirmationDecision.DENY); h.idle()
    }

    @Test fun `a session answer given after the tool was switched off leaves no grant`() {
        val h = Harness(one())
        h.send(); val card = h.card()
        h.settings.setAssistantTool(AssistantToolId.SSH_COMMAND, false); h.chat.toolsChanged(h.settings.assistantAvailability.tools)
        h.chat.answer(card.id, ConfirmationDecision.SESSION); h.idle()
        assertTrue(h.support.grants.snapshot().isEmpty())
    }

    @Test fun `a provider change during the wait stops the Send and runs nothing`() {
        val h = Harness(one())
        h.send(); h.card()
        h.snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "m"), "r2"); h.chat.refresh()
        h.idle()
        assertEquals(0, h.executed.get()); assertNull(h.state.confirmation); eventually("slot free") { h.settings.assistantChat.busy.value.takeIf { !it } }
    }

    @Test fun `clearing the history while the owner is asked stops the Send and leaves no orphaned card`() {
        val h = Harness(one())
        h.send(); val card = h.card()
        runBlocking { h.chat.deleteHistory() }
        h.idle()
        assertEquals(0, h.executed.get()); assertNull(h.state.confirmation); eventually("slot free") { h.settings.assistantChat.busy.value.takeIf { !it } }
        h.chat.answer(card.id, ConfirmationDecision.ONCE); Thread.sleep(100); assertEquals(0, h.executed.get())
    }

    @Test fun `two changing calls in one step are asked one by one and answered separately`() {
        val two = { request: AgentRequest ->
            if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf("a", "b").map { c ->
                ToolCall("id-$c", "ssh_command", buildJsonObject { put("profile_id", "p"); put("connection_id", "c"); put("command", c) }) }) else null
        }
        val h = Harness(emptyList(), custom = two)
        h.send()
        val first = h.card(); assertEquals("a", first.command)
        h.chat.answer(first.id, ConfirmationDecision.DENY)
        val second = eventually("second card") { h.state.confirmation?.takeIf { it.command == "b" } }
        assertEquals(0, h.executed.get()); h.chat.answer(second.id, ConfirmationDecision.ONCE); h.idle()
        assertEquals(listOf("b"), h.commands)
        assertEquals(listOf(ToolOutcome.DECLINED, ToolOutcome.SUCCESS), h.results().map { it.outcome })
    }

    @Test fun `a conversation without a confirmation support refuses a changing call and runs nothing`() {
        val h = Harness(one(), withSupport = false)
        h.send(); h.idle()
        assertEquals(0, h.executed.get()); assertNull(h.state.confirmation)
        val result = h.results().single()
        assertEquals(ToolOutcome.FAILED, result.outcome); assertEquals("Tool was not authorized; nothing was executed.", result.text)
    }

    @Test fun `the question shows only while it is open and the history and field come back after every way it can end`() {
        fun Harness.asks() = state.asking
        // Decline
        val declined = Harness(one()); declined.send(); declined.card(); assertTrue(declined.asks())
        declined.chat.answer(declined.state.confirmation!!.id, ConfirmationDecision.DENY); declined.idle(); assertFalse(declined.asks())
        // Stop
        val stopped = Harness(one()); stopped.send(); stopped.card(); assertTrue(stopped.asks())
        stopped.chat.stop(); stopped.idle(); assertFalse(stopped.asks()); assertNull(stopped.state.confirmation)
    }
}
