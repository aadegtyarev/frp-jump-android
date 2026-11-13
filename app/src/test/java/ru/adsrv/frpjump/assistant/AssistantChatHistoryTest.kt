package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.store.*

class AssistantChatHistoryTest {
    private class Memory : TextStorage {
        var text: String? = null
        var fail = false
        var beforeRead: (() -> Unit)? = null
        var reads = 0
        override fun read(): String? { reads++; beforeRead?.invoke(); return text }
        override fun write(text: String) { check(!fail); this.text = text }
        override fun delete() { check(!fail); text = null }
    }
    private class Box : SecretBox {
        override fun seal(plain: String) = Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun open(sealed: String) = String(Base64.getDecoder().decode(sealed))
        override fun destroy() = Unit
    }
    private val snapshot = AssistantProviderSnapshot(AssistantProviderSettings(model = "test"), "same-revision")
    private fun chat(scope: CoroutineScope, store: AssistantChatHistory, provider: AgentProvider, saved: AssistantProviderSnapshot = snapshot,
        awaitClear: suspend () -> Unit = {}, commitClear: suspend () -> Unit = {}) =
        AssistantConversation(scope, { AssistantAvailability(true) }, { saved }, { provider }, {}, AgentModule(true),
            io = Dispatchers.Unconfined, historyStore = store, awaitHistoryClear = awaitClear, commitHistoryClear = commitClear, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
    private suspend fun AssistantConversation.ready() = withTimeout(3000) { state.first { !it.loading && !it.busy } }

    @Test fun `new owner receives selected target and known diagnostic result without replay`() = runBlocking {
        val file = Memory(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
        val result = ToolResult("lookup", "profile_information", "{\"profile_id\":\"work\",\"connection_id\":\"ssh-root\",\"os\":\"Debian 12\"}", ToolOutcome.SUCCESS)
        val turn = AgentTurn("Find the office board, not the identically named home board", listOf(
            AgentExchange(AgentReply(calls = listOf(ToolCall("lookup", "profile_information", buildJsonObject { put("query", "board") }))), listOf(result)),
            AgentExchange(AgentReply("Selected work/ssh-root; Debian 12"))))
        store.save(SavedChat(records = listOf(SavedTurn.of(turn))).bind(snapshot.settings))
        store.load()
        assertFalse(file.text!!.contains("Debian"))
        var calls = 0
        val restored = chat(scope, AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined), AgentProvider { request ->
            calls++
            assertEquals(TestPrompts.real.system, request.instructions)
            assertEquals("Continue", request.turns.last().user)
            val facts = Json.parseToJsonElement(request.turns.first().exchanges.first().results.single().text).jsonObject
            AgentReply("${facts.getValue("profile_id").jsonPrimitive.content}/${facts.getValue("connection_id").jsonPrimitive.content}: ${facts.getValue("os").jsonPrimitive.content}")
        })
        restored.ready(); assertEquals(0, calls)
        restored.edit("Continue"); restored.send(); restored.ready()
        assertEquals("work/ssh-root: Debian 12", restored.state.value.turns.last().answer)
        assertEquals(1, calls)
        restored.close(); scope.cancel()
    }

    @Test fun `received failure survives following provider error and a fresh owner`() = runBlocking {
        val file = Memory(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
        var calls = 0
        val first = chat(scope, store, AgentProvider {
            if (++calls == 1) AgentReply(calls = listOf(ToolCall("call", "missing", buildJsonObject { put("target", "work/ssh-root") })))
            else error("private diagnostic")
        })
        first.ready(); first.edit("Diagnose work board"); first.send(); first.ready(); first.close(); store.load()
        val next = chat(scope, AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined), AgentProvider { request ->
            val prior = request.turns.first()
            assertEquals("Diagnose work board", prior.user)
            assertEquals(ToolOutcome.UNKNOWN_TOOL, prior.exchanges.first().results.single().outcome)
            assertTrue(prior.exchanges.last().reply.text.contains("unknown"))
            AgentReply(prior.exchanges.first().results.single().text)
        })
        next.ready(); assertTrue(next.state.value.turns.last().interrupted)
        assertEquals("missing", next.state.value.turns.last().toolFailures.single().result.name)
        next.edit("Explain what failed"); next.send(); next.ready()
        assertTrue(next.state.value.turns.last().answer.isNotBlank())
        next.close(); scope.cancel()
    }

    @Test fun `unreadable history cannot be overwritten and changed model keeps readable history`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = Memory().apply { text = "corrupt" }
        val history = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
        val unreadable = chat(scope, history, AgentProvider { error("must not call") })
        unreadable.ready(); assertEquals(ChatHistoryProblem.READ, unreadable.state.value.historyProblem)
        unreadable.edit("overwrite"); unreadable.send(); assertEquals("corrupt", file.text); unreadable.close()
        file.text = null
        history.save(SavedChat(records = listOf(SavedTurn.of(AgentTurn("old task", listOf(AgentExchange(AgentReply("old fact"))))))).bind(snapshot.settings)); history.load()
        val next = snapshot.copy(settings = snapshot.settings!!.copy(model = "new"), revision = "new-revision")
        val changed = chat(scope, history, AgentProvider { request ->
            assertEquals(1, request.turns.size); AgentReply("new context")
        }, next)
        changed.ready(); assertEquals("old fact", changed.state.value.turns.single().answer)
        assertTrue(changed.state.value.contextChanged)
        changed.edit("new task"); changed.send(); changed.ready()
        assertEquals(listOf("old fact", "new context"), changed.state.value.turns.map { it.answer })
        changed.close(); scope.cancel()
    }

    @Test fun `reopen after failed save retains latest target for UI retry and next model request`() = runBlocking {
        for (hasOlderFile in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val file = Memory(); val history = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
            val first = chat(scope, history, AgentProvider { AgentReply(it.turns.last().user) })
            try {
                first.ready()
                if (hasOlderFile) { first.edit("home / home-root"); first.send(); first.ready() }
                file.fail = true
                first.edit("work / work-root"); first.send(); first.ready(); first.close()
                var calls = 0
                val reopened = chat(scope, history, AgentProvider { request ->
                    calls++
                    AgentReply(request.turns.dropLast(1).last().exchanges.last().reply.text)
                })
                try {
                    reopened.ready()
                    assertEquals("latest accepted target must be visible, not older disk data; older=$hasOlderFile",
                        "work / work-root", reopened.state.value.turns.lastOrNull()?.answer)
                    assertEquals(ChatHistoryProblem.WRITE, reopened.state.value.historyProblem)
                    assertEquals(0, calls)
                    file.fail = false; reopened.retryHistory(); reopened.ready()
                    assertNull(reopened.state.value.historyProblem)
                    assertEquals("work / work-root", history.load()!!.records.last().view().answer)
                    reopened.edit("Continue"); reopened.send(); reopened.ready()
                    assertEquals("work / work-root", reopened.state.value.turns.last().answer)
                    assertEquals(1, calls)
                } finally { reopened.close() }
            } finally { first.close(); scope.cancel() }
        }
    }

    @Test fun `duplicate history reloads are ignored while one read is pending`() = runBlocking {
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            val file = Memory().apply { beforeRead = { entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val history = AssistantChatHistory(file, Box(), scope, io)
            val owner = chat(scope, history, AgentProvider { AgentReply("answer") })
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                owner.reloadHistory(); owner.reloadHistory(); release.countDown()
                withContext(io) { }; owner.ready()
                assertEquals("one pending load, not delayed overwrites", 1, file.reads)
            } finally { release.countDown(); owner.close(); scope.cancel() }
        }
    }

    @Test fun `failed clear restores latest unsaved facts and retry can still clear them`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = Memory(); val history = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
        val owner = chat(scope, history, AgentProvider { AgentReply(it.turns.last().user) })
        try {
            owner.ready(); owner.edit("home / home-root"); owner.send(); owner.ready()
            file.fail = true; owner.edit("work / work-root"); owner.send(); owner.ready()
            assertTrue(owner.hideHistory()); owner.deleteHistory(); owner.ready()
            assertEquals(ChatHistoryProblem.CLEAR, owner.state.value.historyProblem)
            assertEquals("work / work-root", owner.state.value.turns.last().answer)
            file.fail = false; owner.retryHistory(); owner.ready()
            assertNull(file.text); assertTrue(owner.state.value.turns.isEmpty())
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun `repeated local preparation failures retain the earlier received tool result`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = AssistantChatHistory(Memory(), Box(), scope, Dispatchers.Unconfined)
        var failPreparation = false; var calls = 0
        val provider = AgentProvider { request ->
            when (++calls) {
                1 -> AgentReply(calls = listOf(ToolCall("received", "missing", buildJsonObject { put("target", "work-root") })))
                2 -> error("model failed after receipt")
                else -> {
                    val previous = request.turns.first().exchanges.first()
                    assertEquals(ToolOutcome.UNKNOWN_TOOL, previous.results.single().outcome)
                    AgentReply(previous.reply.calls.single().arguments.getValue("target").jsonPrimitive.content)
                }
            }
        }
        val owner = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { provider },
            { if (failPreparation) throw ProviderSetupException(ProviderSetupProblem.READ_FAILED) }, AgentModule(true),
            io = Dispatchers.Unconfined, historyStore = history, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        try {
            owner.ready(); owner.edit("Diagnose work-root"); owner.send(); owner.ready()
            failPreparation = true
            repeat(2) { owner.edit("Continue"); owner.send(); owner.ready() }
            assertEquals(2, calls)
            failPreparation = false; owner.edit("Continue"); owner.send(); owner.ready()
            assertEquals("work-root", owner.state.value.turns.last().answer)
            assertEquals(3, calls)
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun `changing only context limits keeps model history and editing draft does not write`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = Memory(); val history = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
        val first = chat(scope, history, AgentProvider { AgentReply("work / work-root") })
        try {
            first.ready(); first.edit("remember target"); assertNull(file.text)
            first.send(); first.ready(); first.close()
            val changed = snapshot.copy(settings = snapshot.settings!!.copy(contextTokens = 32_768, outputTokens = 4096), revision = "changed-limits")
            val reopened = chat(scope, history, AgentProvider { request -> AgentReply(request.turns.first().exchanges.last().reply.text) }, changed)
            try {
                reopened.ready(); assertFalse(reopened.state.value.contextChanged)
                reopened.edit("continue"); reopened.send(); reopened.ready()
                assertEquals("work / work-root", reopened.state.value.turns.last().answer)
            } finally { reopened.close() }
        } finally { first.close(); scope.cancel() }
    }

    @Test fun `shared Undo restores history and committed clear survives route exit`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = Memory(); val history = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
        var expire: (() -> Unit)? = null
        val undo = ru.adsrv.frpjump.undo.PendingUndo(timer = { _, action -> expire = action; {} },
            run = { action -> scope.launch { action() }; Unit }, announce = { _, _ -> })
        val key = "assistant-chat-history"
        val owner = chat(scope, history, AgentProvider { AgentReply("work / work-root") }, awaitClear = { undo.awaitDone(key) })
        try {
            owner.ready(); owner.edit("remember target"); owner.send(); owner.ready()
            assertTrue(owner.hideHistory())
            undo.enqueue(key, commit = { owner.deleteHistory() }, undo = owner::reloadHistory)
            assertNotNull(file.text); assertTrue(owner.state.value.turns.isEmpty())
            undo.undoAll(); owner.ready()
            assertEquals("work / work-root", owner.state.value.turns.single().answer)
            assertTrue(owner.hideHistory())
            undo.enqueue(key, commit = { owner.deleteHistory() }, undo = owner::reloadHistory)
            owner.close()
            val reopened = chat(scope, history, AgentProvider { error("restoration never calls a model") }, awaitClear = { undo.awaitDone(key) })
            try {
                assertTrue(reopened.state.value.loading); assertNotNull(file.text)
                expire!!(); reopened.ready()
                assertNull(file.text); assertTrue(reopened.state.value.turns.isEmpty())
            } finally { reopened.close() }
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun `save failure is recoverable and clear removes both model and visible context`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = Memory(); val history = AssistantChatHistory(file, Box(), scope, Dispatchers.Unconfined)
        val owner = chat(scope, history, AgentProvider { AgentReply("known fact") })
        owner.ready(); file.fail = true; owner.edit("remember me"); owner.send(); owner.ready()
        assertEquals(ChatHistoryProblem.WRITE, owner.state.value.historyProblem)
        file.fail = false; owner.retryHistory(); assertNull(owner.state.value.historyProblem)
        owner.send(); owner.ready(); history.load()
        assertTrue(owner.hideHistory()); owner.deleteHistory()
        assertNull(file.text); assertTrue(owner.state.value.turns.isEmpty())
        owner.close(); scope.cancel()
    }

    /**
     * Р55: a Clear waiting for its Undo, with the shared Undo, a recording notice, a provider that records its requests and a history with
     * one old record. [real] runs the conversation and the store on a real dispatcher (the restore can then be held in flight).
     */
    private inner class Pending(val deferCommit: Boolean = false, real: Boolean = false) : AutoCloseable {
        val dispatcher: CoroutineDispatcher = if (real) Dispatchers.Default else Dispatchers.Unconfined
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val file = Memory(); val history = AssistantChatHistory(file, Box(), scope, dispatcher)
        val key = "assistant-chat-history"
        val notice = java.util.concurrent.CopyOnWriteArrayList<Map<ru.adsrv.frpjump.undo.UndoKind, Int>?>()
        val held = mutableListOf<suspend () -> Unit>()
        val undo = ru.adsrv.frpjump.undo.PendingUndo(timer = { _, _ -> {} },
            run = { action -> if (deferCommit) held += action else { scope.launch { action() }; Unit } }, announce = { counts, _ -> notice += counts })
        val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
        val owner = AssistantConversation(scope, { AssistantAvailability(true) }, { snapshot }, { AgentProvider { request -> requests += request; AgentReply("answer ${requests.size}") } }, {}, AgentModule(true),
            io = dispatcher, historyStore = history, awaitHistoryClear = { undo.awaitDone(key) },
            commitHistoryClear = { undo.commit(key); undo.awaitDone(key) }, prompts = TestPrompts.lazy, confirmations = TestConfirmations.off)
        init {
            runBlocking { owner.ready(); owner.edit("old question"); owner.send(); owner.ready() }
            requests.clear()
        }
        fun clear() { assertTrue(owner.hideHistory()); undo.enqueue(key, commit = { owner.deleteHistory() }, undo = owner::reloadHistory) }
        suspend fun settled() = withTimeout(5000) { owner.state.first { !it.loading && !it.restoring && !it.clearing && !it.sending && !it.busy } }
        val noticeShown get() = notice.lastOrNull() != null
        /** The saved file as the store wrote it: sealed, so it is opened before any check of its content. */
        fun savedText(): String? = file.text?.let { Box().open(it) }
        override fun close() { owner.close(); scope.cancel() }
    }

    @Test fun `typing is free while a Clear waits`() = runBlocking {
        Pending().use { f ->
            f.clear()
            val state = f.owner.state.value
            assertTrue(state.clearing); assertFalse(state.loading); assertTrue(state.fieldEnabled)
            assertTrue(state.turns.isEmpty()); assertEquals("", state.draft)
            f.owner.edit("typed now"); assertEquals("typed now", f.owner.state.value.draft)
            assertTrue(f.owner.state.value.sendEnabled(true))
        }
    }

    @Test fun `the draft on Undo is the saved one, a line break, then what was typed during the clear`() = runBlocking {
        Pending().use { f ->
            f.owner.edit("saved draft"); f.clear(); f.owner.edit("typed now")
            f.undo.undoAll(); f.settled()
            assertEquals("old question", f.owner.state.value.turns.single().user)
            assertEquals("saved draft\ntyped now", f.owner.state.value.draft)
        }
        Pending().use { f ->
            f.owner.edit("saved draft"); f.clear(); f.undo.undoAll(); f.settled()
            assertEquals("saved draft", f.owner.state.value.draft)
        }
        Pending().use { f ->
            f.clear(); f.owner.edit("typed only"); f.undo.undoAll(); f.settled()
            assertEquals("typed only", f.owner.state.value.draft)
        }
    }

    @Test fun `a quote added during the clear is part of the typed text`() = runBlocking {
        Pending().use { f ->
            f.owner.edit("saved draft"); f.clear()
            assertTrue(f.owner.appendToDraft("quoted"))
            f.undo.undoAll(); f.settled()
            assertEquals("saved draft\nquoted", f.owner.state.value.draft)
        }
    }

    @Test fun `Undo before any send restores everything and keeps the draft`() = runBlocking {
        Pending().use { f ->
            f.owner.edit("saved draft"); f.clear()
            f.undo.undoAll(); f.settled()
            assertEquals("old question", f.owner.state.value.turns.single().user)
            assertEquals("saved draft", f.owner.state.value.draft)
            assertTrue(f.requests.isEmpty()); assertFalse(f.noticeShown)
        }
    }

    @Test fun `the old records are not held while the clear waits`() = runBlocking {
        Pending().use { f ->
            f.clear()
            val state = f.owner.state.value
            assertTrue(state.turns.isEmpty()); assertFalse(state.summarized); assertFalse(state.contextChanged)
            f.owner.refresh()
            assertFalse(f.owner.state.value.summarized); assertFalse(f.owner.state.value.contextChanged)
        }
    }

    @Test fun `a Send while the Clear waits commits it first and goes into an empty conversation`() = runBlocking {
        Pending().use { f ->
            f.clear(); assertTrue(f.noticeShown)
            f.owner.edit("new question"); f.owner.send(); f.settled()
            assertFalse(f.noticeShown); assertTrue(f.undo.waitingKeys().isEmpty()); assertFalse(f.key in f.undo.hidden.value)
            val request = f.requests.single()
            assertEquals(listOf("new question"), request.turns.map { it.user })
            assertEquals(listOf("new question"), f.owner.state.value.turns.map { it.user })
            f.history.load()
            assertFalse(f.savedText()!!.contains("old question"))
            f.undo.undoAll(); f.settled()
            assertEquals(listOf("new question"), f.owner.state.value.turns.map { it.user })
        }
    }

    @Test fun `a waiting Send shows as sent and is not editable until it starts`() = runBlocking {
        Pending(deferCommit = true).use { f ->
            f.clear(); f.owner.edit("new question"); f.owner.send()
            val waiting = f.owner.state.value
            assertTrue(waiting.sending); assertEquals("new question", waiting.pendingText); assertEquals("", waiting.draft)
            assertFalse(waiting.fieldEnabled); assertFalse(waiting.sendEnabled(true)); assertFalse(waiting.menuEnabled)
            f.owner.edit("ignored"); assertEquals("", f.owner.state.value.draft)
            f.held.forEach { it() }; f.settled()
            assertEquals(listOf("new question"), f.requests.single().turns.map { it.user })
            assertFalse(f.owner.state.value.sending)
        }
    }

    @Test fun `a Send after Undo went into the restored conversation once`() = runBlocking {
        Pending().use { f ->
            f.clear(); f.undo.undoAll()
            f.owner.edit("follow-up"); f.owner.send(); f.settled()
            assertEquals(listOf("old question", "follow-up"), f.requests.single().turns.map { it.user })
            assertEquals(listOf("old question", "follow-up"), f.owner.state.value.turns.map { it.user })
        }
    }

    @Test fun `a Send right after Undo waits for the restore on a real dispatcher and goes into the restored chat`() = runBlocking {
        Pending(real = true).use { f ->
            f.clear()
            val inRead = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            f.file.beforeRead = { inRead.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            f.undo.undoAll()
            assertTrue(inRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val restoring = f.owner.state.value
            assertTrue(restoring.restoring); assertTrue(restoring.fieldEnabled); assertFalse(restoring.menuEnabled); assertFalse(restoring.sendEnabled(true))
            f.owner.edit("follow-up"); assertEquals("follow-up", f.owner.state.value.draft)
            f.owner.send()
            release.countDown(); f.settled()
            withTimeout(5000) { while (f.requests.isEmpty()) delay(5) }
            f.settled()
            assertEquals(listOf("old question", "follow-up"), f.requests.single().turns.map { it.user })
        }
    }

    @Test fun `once the commit started Undo is gone and the Send goes after it, losing and duplicating nothing`() = runBlocking {
        Pending(deferCommit = true).use { f ->
            f.clear(); f.owner.edit("new question"); f.owner.send()
            // The commit has started (it waits to run): Undo is no longer offered and does nothing.
            assertTrue(f.undo.committing(f.key)); assertFalse(f.noticeShown)
            f.undo.undoAll(); assertTrue(f.requests.isEmpty())
            f.held.forEach { it() }; f.settled()
            assertEquals(listOf("new question"), f.requests.single().turns.map { it.user })
            assertEquals(listOf("new question"), f.owner.state.value.turns.map { it.user })
        }
    }

    @Test fun `a failed commit sends nothing keeps the draft and the known data and shows the failure`() = runBlocking {
        Pending().use { f ->
            f.file.fail = true
            f.clear(); f.owner.edit("keep me"); f.owner.send(); f.settled()
            assertTrue(f.requests.isEmpty())
            assertEquals("keep me", f.owner.state.value.draft); assertFalse(f.owner.state.value.sending)
            assertEquals(ChatHistoryProblem.CLEAR, f.owner.state.value.historyProblem)
            assertEquals("old question", f.owner.state.value.turns.single().user)
            assertFalse(f.owner.state.value.retryEnabled && f.owner.state.value.clearing)
            // Retry clears the history and keeps the message in the draft.
            f.file.fail = false; f.owner.retryHistory(); f.settled()
            assertTrue(f.owner.state.value.turns.isEmpty())
            assertEquals("keep me", f.owner.state.value.draft)
        }
    }

    @Test fun `the conversation stays open while a clear waits and closes when it ends`() = runBlocking {
        Pending().use { f ->
            f.clear(); f.owner.edit("typed")
            assertFalse(f.owner.closeIfIdle())
            f.undo.commit(f.key); f.settled()
            assertEquals("typed", f.owner.state.value.draft)
            assertTrue(f.owner.closeIfIdle())
            // The draft typed during the clear was saved when the conversation closed.
            f.history.load()
            assertEquals("typed", (f.history.load() ?: SavedChat()).draft)
        }
    }
}
