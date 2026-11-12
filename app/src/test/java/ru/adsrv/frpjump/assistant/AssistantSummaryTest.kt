package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.store.*

class AssistantSummaryTest {
    private class Memory : TextStorage {
        @Volatile var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }
    private class Box : SecretBox {
        override fun seal(plain: String) = Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun open(sealed: String) = String(Base64.getDecoder().decode(sealed))
        override fun destroy() = Unit
    }
    private fun records(): List<SavedTurn> {
        val args = buildJsonObject { put("profile_id", "work"); put("connection_id", "work-root") }
        val observed = AgentTurn("Inspect the Work board, not the identically named Home board", listOf(
            AgentExchange(AgentReply(calls=listOf(ToolCall("diagnostic", "ssh_command", args))),
                listOf(ToolResult("diagnostic","ssh_command","target=work-root done=diagnostic",ToolOutcome.SUCCESS))),
            AgentExchange(AgentReply(calls=listOf(ToolCall("upgrade","ssh_command",args))),
                listOf(ToolResult("upgrade","ssh_command","unknown=upgrade",ToolOutcome.TIMEOUT))),
            AgentExchange(AgentReply("Diagnostic received; upgrade outcome unknown, do not repeat it."))))
        return listOf(SavedTurn.of(observed)) + (1..39).map {
            val reply = AgentExchange(AgentReply("received $it " + "x".repeat(160)))
            SavedTurn.of(AgentTurn("Progress $it", listOf(reply)))
        }
    }
    private fun facts(text: String) = listOf("target","done","unknown").joinToString("; ") { key ->
        "$key=" + (Regex("$key=([a-z-]+)").find(text)?.groupValues?.get(1) ?: error("Missing observed $key"))
    }
    private suspend fun AssistantConversation.ready() = withTimeout(8000) { state.first { !it.loading && !it.busy } }

    @Test fun `invalid failed and nonreducing summaries retain last good data`() = runBlocking {
        val model=AgentProvider { error("No main request") }
        val initial=SavedChat(records=records()+SavedTurn("Continue"),summary=SavedSummary("target=work-root; done=diagnostic; unknown=upgrade",1,0))
        val invalid=listOf<suspend (AgentRequest)->AgentReply>(
            { throw ru.adsrv.frpjump.agent.openai.OpenAiProviderException(ru.adsrv.frpjump.agent.openai.ProviderFailure.HTTP,400) },
            { AgentReply("x".repeat(4001)) }, { AgentReply(calls=listOf(ToolCall("bad","ssh_command",buildJsonObject{}))) })
        for (response in invalid) {
            var doc=initial
            val engine=AssistantSummary(AssistantProviderSettings(contextTokens=12_000,outputTokens=512),model,{doc},{doc=doc.copy(summary=it)},response,TestPrompts.real.summary)
            try { engine.prepare(AgentRequest("base",emptyList(),listOf(AgentTurn("Continue")),512));fail("Expected rejection") } catch (_:Exception) { }
            assertSame(initial,doc)
        }
        var small=initial.copy(records=initial.records.take(2)+SavedTurn("Continue"),summary=null)
        val before=small
        val noProgress=AssistantSummary(AssistantProviderSettings(contextTokens=12_000,outputTokens=512,summaryPercent=1),model,{small},{small=small.copy(summary=it)},{AgentReply("x".repeat(4000))},TestPrompts.real.summary)
        try { noProgress.prepare(AgentRequest("base",emptyList(),listOf(AgentTurn("Continue")),512));fail("Expected no-progress rejection") } catch (_:IllegalStateException) { }
        assertSame(before,small)
    }

    @Test fun `one long current turn summarizes older complete pairs but retains latest pair`() = runBlocking {
        val arguments=buildJsonObject { put("connection_id","work-root") }
        val blocks=(0 until 40).map { i -> AgentExchange(AgentReply(calls=listOf(ToolCall("call-$i","ssh_command",arguments))),
            listOf(ToolResult("call-$i","ssh_command",if(i==0) "target=work-root done=diagnostic unknown=upgrade" else "received $i "+"x".repeat(160),ToolOutcome.SUCCESS))) }
        val turn=AgentTurn("Continue Work, not Home",blocks)
        var doc=SavedChat(records=listOf(SavedTurn.of(turn)))
        val model=AgentProvider { AgentReply(facts(it.turns.single().user)) }
        val engine=AssistantSummary(AssistantProviderSettings(contextTokens=12_000,outputTokens=512),model,{doc},{doc=doc.copy(summary=it)},model::complete,TestPrompts.real.summary)
        val result=engine.prepare(AgentRequest("base",emptyList(),listOf(turn),512))
        assertEquals(0,doc.summary!!.turn);assertTrue(doc.summary!!.exchange in 1 until 40)
        assertEquals(40,doc.records.single().exchanges.size)
        assertEquals(blocks.last(),result.turns.last().exchanges.last())
        assertTrue(result.turns.first().exchanges.single().reply.text.contains("target=work-root"))
    }

    @Test fun `summary requests use the summary prompt without tools and the main request keeps the system prompt`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined); val runtime=AssistantChatRuntime()
        val history=AssistantChatHistory(Memory(),Box(),scope,Dispatchers.Unconfined)
        val snapshot=AssistantProviderSnapshot(AssistantProviderSettings(model="chat",contextTokens=12_000,outputTokens=512),"revision")
        history.save(SavedChat(records=records()).bind(snapshot.settings));history.load()
        val seen=java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
        val model=AgentProvider { request -> seen+=request
            if(request.instructions==TestPrompts.real.summary) AgentReply("target=work-root; done=diagnostic; unknown=upgrade") else AgentReply("answer") }
        val chat=AssistantConversation(scope,{AssistantAvailability(true,tools=ru.adsrv.frpjump.settings.AssistantTools(search=true))},{snapshot},{model},{},AgentModule(true),io=Dispatchers.Unconfined,runtime=runtime,historyStore=history,prompts=TestPrompts.lazy, confirmations = null)
        try {
            chat.ready();chat.edit("Continue");chat.send();chat.ready()
            val summaries=seen.filter { it.instructions==TestPrompts.real.summary }; val mains=seen-summaries.toSet()
            assertTrue(summaries.isNotEmpty());assertTrue(summaries.all { it.tools.isEmpty() })
            assertEquals(1,mains.size);assertEquals(TestPrompts.real.system,mains.single().instructions)
            assertEquals(listOf("web_search"),mains.single().tools.map { it.name })
            assertTrue(mains.single().turns.first().user.startsWith("Earlier conversation summary"))
        } finally { chat.close();scope.cancel() }
    }

    @Test fun `stop during noncooperative summary holds slot and suppresses late private data`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined); val runtime=AssistantChatRuntime()
        val history=AssistantChatHistory(Memory(),Box(),scope,Dispatchers.Unconfined)
        val snapshot=AssistantProviderSnapshot(AssistantProviderSettings(model="chat",contextTokens=12_000,outputTokens=512),"revision")
        history.save(SavedChat(records=records()).bind(snapshot.settings));history.load()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var mainCalls=0
        val model=AgentProvider { request ->
            if(request.instructions==TestPrompts.real.summary) { entered.complete(Unit);withContext(NonCancellable) { release.await() };AgentReply("late-private target=work-root") }
            else { mainCalls++;AgentReply("must not run") }
        }
        val chat=AssistantConversation(scope,{AssistantAvailability(true)},{snapshot},{model},{},AgentModule(true),io=Dispatchers.Unconfined,runtime=runtime,historyStore=history,prompts=TestPrompts.lazy, confirmations = null)
        try {
            chat.ready();chat.edit("Continue");chat.send();withTimeout(3000) { entered.await() }
            chat.stop();assertTrue(chat.state.value.stopping);assertTrue(runtime.occupied())
            release.complete(Unit);chat.ready();assertFalse(runtime.occupied());assertEquals(0,mainCalls)
            assertNull(history.load()!!.summary);assertFalse(chat.state.value.toString().contains("late-private"))
        } finally { release.complete(Unit);chat.close();scope.cancel() }
    }

    @Test fun `every provider call including the summary call is admitted and a refused summary dispatch sends nothing`() = runBlocking {
        for (refuse in listOf(false, true)) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined); val runtime=AssistantChatRuntime()
            val history=AssistantChatHistory(Memory(),Box(),scope,Dispatchers.Unconfined)
            val snapshot=AssistantProviderSnapshot(AssistantProviderSettings(model="chat",contextTokens=12_000,outputTokens=512),"revision")
            history.save(SavedChat(records=records()).bind(snapshot.settings));history.load()
            var summaryCalls=0;var mainCalls=0;var dispatches=0
            val model=AgentProvider { request ->
                if(request.instructions==TestPrompts.real.summary) { summaryCalls++;AgentReply("target=work-root; done=diagnostic; unknown=upgrade") }
                else { mainCalls++;AgentReply("answer") }
            }
            lateinit var chat: AssistantConversation
            chat=AssistantConversation(scope,{AssistantAvailability(true)},{snapshot},{model},{},AgentModule(true),io=Dispatchers.Unconfined,runtime=runtime,historyStore=history,prompts=TestPrompts.lazy, confirmations = null)
            // The summary call is the first provider call of this Send; a Stop that commits just before its start check must refuse it.
            runtime.probes=ChatProbes(
                before={ category -> if(category=="dispatch" && refuse && dispatches==0) chat.stop() },
                checked={ category -> if(category=="dispatch") dispatches++ })
            try {
                chat.ready();chat.edit("Continue");chat.send();chat.ready()
                if(refuse) { assertEquals(0,summaryCalls);assertEquals(0,mainCalls);assertEquals(0,dispatches) }
                else { assertTrue("a summary request was made",summaryCalls>=1);assertEquals(1,mainCalls);assertEquals(summaryCalls+mainCalls,dispatches) }
            } finally { chat.close();scope.cancel() }
        }
    }

    @Test fun `a summarizing Send commits under every guarded category and each commit is already persisted`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined); val runtime=AssistantChatRuntime()
        // The store writes on real IO threads, so the probe can wait for the file while the conversation thread is blocked.
        val file=Memory(); val history=AssistantChatHistory(file,Box(),CoroutineScope(SupervisorJob()+Dispatchers.IO),Dispatchers.IO)
        val snapshot=AssistantProviderSnapshot(AssistantProviderSettings(model="chat",contextTokens=12_000,outputTokens=512),"revision")
        history.save(SavedChat(records=records()).bind(snapshot.settings));history.load()
        val model=AgentProvider { request ->
            if(request.instructions==TestPrompts.real.summary) AgentReply("target=work-root; done=diagnostic; unknown=upgrade") else AgentReply("final-answer")
        }
        val chat=AssistantConversation(scope,{AssistantAvailability(true)},{snapshot},{model},{},AgentModule(true),io=Dispatchers.Unconfined,runtime=runtime,historyStore=history,prompts=TestPrompts.lazy, confirmations = null)
        val categories=Collections.synchronizedList(mutableListOf<String>()); var presentations=0
        val problems=Collections.synchronizedList(mutableListOf<String>())
        fun awaitSaved(marker: String) {
            val end=System.nanoTime()+10_000_000_000L
            while(file.text?.let { String(Base64.getDecoder().decode(it)) }?.contains(marker)!=true) {
                if(System.nanoTime()>end) { problems+="$marker was not saved before its projection"; return }
                Thread.sleep(5)
            }
        }
        runtime.probes=ChatProbes(before={ category ->
            // A projection follows its committed update/finish: that document must already be written to the history file
            // (a finished Send clears the draft; the older records are COMPLETE already).
            if(category=="presentation") {
                val first=presentations++==0
                if(categories.lastOrNull()!=(if(first) "history" else "answer")) problems+="projection after ${categories.lastOrNull()}"
                awaitSaved(if(first) "final-answer" else "\"draft\":\"\"")
            }
            categories+=category
        })
        try {
            chat.ready();chat.edit("Continue");chat.send();chat.ready()
            assertTrue("categories $categories",categories.containsAll(listOf("admission","dispatch","progress","summary","history","presentation","answer")))
            assertEquals(2,presentations); assertEquals(emptyList<String>(),problems.toList())
            assertTrue(chat.state.value.summarized)
        } finally { chat.close();scope.cancel() }
    }

    @Test fun `summary provider failure reports the provider status on the summary problem`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val history=AssistantChatHistory(Memory(),Box(),scope,Dispatchers.Unconfined)
        val snapshot=AssistantProviderSnapshot(AssistantProviderSettings(model="chat",contextTokens=12_000,outputTokens=512),"revision")
        history.save(SavedChat(records=records()).bind(snapshot.settings));history.load()
        val model=AgentProvider { request ->
            if(request.instructions==TestPrompts.real.summary) throw ru.adsrv.frpjump.agent.openai.OpenAiProviderException(ru.adsrv.frpjump.agent.openai.ProviderFailure.HTTP,400)
            AgentReply("must not run")
        }
        val chat=AssistantConversation(scope,{AssistantAvailability(true)},{snapshot},{model},{},AgentModule(true),io=Dispatchers.Unconfined,historyStore=history,prompts=TestPrompts.lazy, confirmations = null)
        try {
            chat.ready();chat.edit("Continue");chat.send();chat.ready()
            assertEquals(ChatProblem.SUMMARY,chat.state.value.problem);assertEquals(400,chat.state.value.problemStatus)
        } finally { chat.close();scope.cancel() }
    }

    @Test fun `real requests summarize then a fresh conversation continues exact target without replay`() = runBlocking {
        for (override in listOf(null,"summary-model")) {
            val seen = Collections.synchronizedList(mutableListOf<JsonObject>())
            val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
            val workerErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            val worker = thread(isDaemon=true, name="summary-fixture") {
              try { while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout=5000
                try {
                    val input=socket.getInputStream(); val header=StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val byte=input.read();check(byte>=0 && header.length<16384);header.append(byte.toChar()) }
                    assertTrue(header.startsWith("POST /v1/chat/completions HTTP/1.1"))
                    assertTrue(header.contains("Authorization: Bearer synthetic-key",ignoreCase=true))
                    val length=header.lines().first { it.startsWith("Content-Length:",true) }.substringAfter(':').trim().toInt()
                    val body=Json.parseToJsonElement(input.readNBytes(length).toString(Charsets.UTF_8)).jsonObject
                    seen += body
                    val messages=body.getValue("messages").jsonArray.map { it.jsonObject }
                    val text=messages.joinToString("\n") { it["content"]?.jsonPrimitive?.content.orEmpty() }
                    val summary=messages.first().getValue("content").jsonPrimitive.content==TestPrompts.real.summary
                    if (summary) { assertNull(body["tools"]); assertEquals(override?:"chat-model",body.getValue("model").jsonPrimitive.content) }
                    else { assertEquals("chat-model",body.getValue("model").jsonPrimitive.content); assertFalse(text.contains("Inspect the Work board")) }
                    val answer=facts(text)
                    val response=buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                        put("finish_reason","stop");putJsonObject("message") { put("role","assistant");put("content",answer) }
                    }) } }.toString().toByteArray()
                    val output=socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(response);output.flush()
                } catch (failure:Throwable) { workerErrors += failure }
              } } catch (failure:java.net.SocketException) { if(!server.isClosed) workerErrors += failure }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try {
                val box=Box();val provider=AssistantProviderStore(Memory(),box)
                provider.save(AssistantProviderDraft(AssistantProviderSettings(baseUrl="http://127.0.0.1:${server.localPort}/v1/",model="chat-model",contextTokens=12_000,outputTokens=512,summaryModel=override),null,"synthetic-key"))
                val file=Memory();val history=AssistantChatHistory(file,box,scope)
                val original=records();history.save(SavedChat(records=original).bind(provider.currentSnapshot.settings));history.load()
                fun chat(store:AssistantChatHistory)=AssistantConversation(scope,{AssistantAvailability(true)},{provider.currentSnapshot},
                    { provider.provider(AssistantProviderDraft(it.settings!!,it.revision)) },provider::requireRevision,AgentModule(true),historyStore=store,prompts=TestPrompts.lazy, confirmations = null)
                val first=chat(history);first.ready();assertTrue(seen.isEmpty())
                first.edit("Continue using the selected target and observed outcomes; do not repeat actions");first.send();first.ready()
                assertNull(first.state.value.problem);assertEquals("target=work-root; done=diagnostic; unknown=upgrade",first.state.value.turns.last().answer)
                assertEquals(41,first.state.value.turns.size);assertTrue(first.state.value.summarized)
                first.close();val stored=history.load()!!;assertNotNull(stored.summary);assertEquals(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SavedTurn.serializer()),original),Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SavedTurn.serializer()),stored.records.take(40)))
                val reopened=chat(AssistantChatHistory(file,box,scope));reopened.ready();val before=seen.size
                reopened.edit("Продолжай");reopened.send();reopened.ready()
                assertNull(reopened.state.value.problem);assertEquals("target=work-root; done=diagnostic; unknown=upgrade",reopened.state.value.turns.last().answer)
                assertEquals(before+1,seen.size);assertEquals(42,reopened.state.value.turns.size)
                assertEquals("chat-model",provider.currentSnapshot.settings!!.model);reopened.close()
                val summaries=seen.count { it.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content==TestPrompts.real.summary }
                assertTrue(summaries in 1..2)
                assertTrue(workerErrors.toString(),workerErrors.isEmpty())
            } finally { scope.cancel();server.close();worker.join(1000) }
        }
    }
}
