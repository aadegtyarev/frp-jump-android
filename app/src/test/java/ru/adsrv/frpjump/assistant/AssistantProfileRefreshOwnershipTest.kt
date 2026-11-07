package ru.adsrv.frpjump.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox

/** Tests the actual ordinary HTTP starts/exits, not just eventual answer publication. */
class AssistantProfileRefreshOwnershipTest {
    private val causes = listOf("stop", "exit", "disable", "off-on", "revision", "read-problem")
    private class Hold(count: Int = 1) {
        val entered = CountDownLatch(count)
        val release = CountDownLatch(1)
        fun waitHere() { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        fun entered(label: String) = assertTrue(label, entered.await(2, TimeUnit.SECONDS))
    }
    private class Chat(val f: SyntheticProfileInformation) : AutoCloseable {
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantProfileInformation = true) } }
        val file = MemoryStorage()
        val store = AssistantProviderStore(file, object : SecretBox {
            override fun seal(plain: String) = "sealed"
            override fun open(sealed: String): String = error("No credential unseal")
            override fun destroy() = Unit
        }, settings.assistantChat).apply { save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key")) }
        val originalFile = file.text
        val unexpected = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> unexpected += error })
        val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
        val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
        val old = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
            AgentProvider { request ->
                requests += request
                if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("information", "profile_information", buildJsonObject { })))
                else AgentReply("Old information answer")
            }
        }, store::requireRevision, module, runtime = settings.assistantChat, profileInformation = f.information)
        var preparations = 0
        val newer = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
            preparations++; AgentProvider { AgentReply("New explicit answer") }
        }, store::requireRevision, module, runtime = settings.assistantChat, profileInformation = f.information)
        val unrelated = scope.launch { awaitCancellation() }
        fun send() { old.edit("Old information question"); old.send() }
        fun revoke(cause: String, refresh: Boolean = true) {
            when (cause) {
                "stop" -> old.stop()
                "exit" -> old.close()
                "disable", "off-on" -> {
                    settings.update { it.copy(assistantEnabled = false) }
                    if (cause == "off-on") settings.update { it.copy(assistantEnabled = true) }
                }
                "revision" -> store.save(AssistantProviderDraft(AssistantProviderSettings(model = "next"), store.currentSnapshot.revision))
                "read-problem" -> { file.text = "invalid"; store.reload() }
                else -> error("Unknown cause")
            }
            if (refresh) old.refresh()
        }
        fun recover() {
            settings.update { it.copy(assistantEnabled = true) }
            if (store.currentSnapshot.problem != null) { file.text = originalFile; store.reload() }
        }
        suspend fun idle() = withTimeout(4000) { settings.assistantChat.busy.first { !it } }
        override fun close() { old.close(); newer.close(); scope.cancel(); assertEquals("Unexpected owner failures", emptyList<Throwable>(), unexpected) }
    }

    @Test fun `AUTH-1 revoke wins before all four actual starts for every revocation cause`() = runBlocking {
        for (cause in causes) SyntheticProfileInformation().use { f -> Chat(f).use { c ->
            val hold = Hold(4)
            c.settings.assistantChat.beforeAction = { category -> if (category.startsWith("profile-") && category.endsWith("-dispatch")) hold.waitHere() }
            val enqueueGuards = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            f.onEnqueue = { enqueueGuards += Thread.holdsLock(c.settings.assistantChat.gate) }
            try {
                c.send(); hold.entered(cause)
                // For committed authority changes deliberately omit the observer cancellation:
                // guarded enqueue must reject canonical authority, not merely a cancelled Job.
                c.revoke(cause, refresh = false)
                hold.release.countDown(); c.idle()
                assertEquals("$cause: no /version or /devices in either profile", emptyList<String>(), f.enqueues)
                assertTrue(enqueueGuards.all { it })
                assertTrue(c.old.state.value.turns.isEmpty()); assertEquals(1, c.requests.size)
                assertTrue(c.old.state.value.currentSources.isEmpty()); assertTrue(c.unrelated.isActive)
            } finally { hold.release.countDown() }
        } }
    }

    @Test fun `AUTH-1 actual start wins existing guard before revoke for both endpoints and every cause`() = runBlocking {
        for (operation in listOf("version", "devices")) for (cause in causes) SyntheticProfileInformation().use { f -> Chat(f).use { c ->
            val hold = Hold(); val responseHold = Hold(); val once = AtomicBoolean()
            f.beforeResponse = responseHold::waitHere
            c.settings.assistantChat.checkedAction = { category -> if (category == "profile-$operation-dispatch" && once.compareAndSet(false, true)) hold.waitHere() }
            val enqueueGuards = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            f.onEnqueue = { enqueueGuards += Thread.holdsLock(c.settings.assistantChat.gate) }
            val revokeStarted = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val revoke = Thread { try { revokeStarted.countDown(); c.revoke(cause) } catch (e: Throwable) { failure.set(e) } }
            try {
                c.send(); hold.entered("$cause/$operation")
                revoke.start(); assertTrue(revokeStarted.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (revoke.isAlive && revoke.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals("Revocation must wait for actual enqueue's common guard", Thread.State.BLOCKED, revoke.state)
                hold.release.countDown(); revoke.join(2000); assertFalse(revoke.isAlive); failure.get()?.let { throw it }
                responseHold.release.countDown(); c.idle()
                assertTrue("Winning request actually enqueued: $cause/$operation", f.enqueues.any { it.endsWith("/$operation") })
                assertTrue("Every actual enqueue held existing guard: $cause/$operation", enqueueGuards.isNotEmpty() && enqueueGuards.all { it })
                assertTrue(c.old.state.value.turns.isEmpty()); assertTrue(c.old.state.value.currentSources.isEmpty())
                assertTrue(c.unrelated.isActive)
            } finally { hold.release.countDown(); responseHold.release.countDown(); revoke.join(2000) }
        } }
    }

    @Test fun `OWN-EXIT-1 interceptor body and callback exit retain shared slot across all revocations and reopen`() = runBlocking {
        for (stage in listOf("interceptor", "body", "callback")) for (cause in causes) SyntheticProfileInformation().use { f -> Chat(f).use { c ->
            val hold = Hold(4)
            val awaitingExit = CountDownLatch(4)
            when (stage) {
                "interceptor" -> f.beforeResponse = hold::waitHere
                "body" -> f.api.beforeOwnedResponseRead = hold::waitHere
                "callback" -> f.api.beforeOwnedCallbackExit = hold::waitHere
            }
            f.api.beforeOwnedAwaitExit = { awaitingExit.countDown() }
            try {
                c.send(); hold.entered("$stage/$cause")
                assertEquals(setOf("work.example/devices", "work.example/version", "home.example/devices", "home.example/version"), f.enqueues.toSet())
                c.revoke(cause)
                assertTrue("$stage/$cause reached cancelled/returned coroutine exit wait", awaitingExit.await(2, TimeUnit.SECONDS))
                assertTrue("$stage/$cause outstanding AsyncCall must own global slot", c.settings.assistantChat.busy.value)
                c.recover() // Re-enable/setup recovery cannot resurrect old work or free its slot.
                c.newer.edit("New explicit input"); c.newer.send()
                assertEquals(0, c.preparations); assertNull(c.newer.state.value.pendingText)
                assertTrue(c.newer.state.value.busy); assertTrue(c.newer.state.value.turns.isEmpty())
                assertTrue(c.old.state.value.turns.isEmpty()); assertEquals(1, c.requests.size)
                hold.release.countDown(); c.idle()
                assertEquals("No queued replacement", 0, c.preparations)
                assertTrue(c.old.state.value.turns.isEmpty()); assertTrue(c.old.state.value.currentSources.isEmpty())
                c.newer.send(); c.idle()
                assertEquals(1, c.preparations); assertEquals("New explicit answer", c.newer.state.value.turns.single().answer)
                assertTrue(c.unrelated.isActive)
            } finally { hold.release.countDown() }
        } }
    }

    @Test fun `tool-switch-only commit does not revoke admitted refresh or discard history`() = runBlocking {
        SyntheticProfileInformation().use { f -> Chat(f).use { c ->
            val hold = Hold(4)
            f.api.beforeOwnedCallbackExit = hold::waitHere
            try {
                c.send(); hold.entered("tool-only")
                c.settings.setAssistantTool("profile_information", false); c.old.refresh()
                assertTrue(c.settings.assistantChat.busy.value)
                hold.release.countDown(); c.idle()
                assertEquals("Old information answer", c.old.state.value.turns.single().answer)
                assertEquals(2, c.requests.size); assertEquals(4, f.enqueues.size)
                assertEquals(listOf("profile_information"), c.requests.last().tools.map { it.name })
            } finally { hold.release.countDown() }
        } }
    }
}
