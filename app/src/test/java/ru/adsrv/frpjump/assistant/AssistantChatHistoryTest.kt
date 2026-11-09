package ru.adsrv.frpjump.assistant

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
        awaitClear: suspend () -> Unit = {}) =
        AssistantConversation(scope, { AssistantAvailability(true) }, { saved }, { provider }, {}, AgentModule(true),
            io = Dispatchers.Unconfined, historyStore = store, awaitHistoryClear = awaitClear)
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
            assertEquals(AssistantConversation.INSTRUCTIONS, request.instructions)
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
            io = Dispatchers.Unconfined, historyStore = history)
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
}
