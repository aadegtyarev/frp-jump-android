package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
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
    private val causes = listOf("stop", "force-close", "disable", "off-on", "revision", "read-problem")
    private class Hold(count: Int = 1) {
        val entered = CountDownLatch(count)
        val release = CountDownLatch(1)
        fun waitHere() { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
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
        }, store::requireRevision, module, runtime = settings.assistantChat, profileInformation = f.information, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        var preparations = 0
        val newer = AssistantConversation(scope, { settings.assistantAvailability }, { store.currentSnapshot }, {
            preparations++; AgentProvider { AgentReply("New explicit answer") }
        }, store::requireRevision, module, runtime = settings.assistantChat, profileInformation = f.information, prompts = TestPrompts.placeholder, confirmations = TestConfirmations.off)
        val unrelated = scope.launch { awaitCancellation() }
        fun send() { old.edit("Old information question"); old.send() }
        fun revoke(cause: String, refresh: Boolean = true) {
            when (cause) {
                "stop" -> old.stop()
                "force-close" -> old.close()
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
            c.settings.assistantChat.probes = ChatProbes(before = { category -> if (category.startsWith("profile-") && category.endsWith("-dispatch")) hold.waitHere() })
            try {
                c.send(); hold.entered(cause)
                // For committed authority changes deliberately omit the observer cancellation:
                // the start check must reject canonical authority, not merely a cancelled Job.
                c.revoke(cause, refresh = false)
                hold.release.countDown(); c.idle()
                assertEquals("$cause: no /version or /devices in either profile", emptyList<String>(), f.enqueues)
                assertTrue(c.old.state.value.turns.isEmpty()); assertEquals(1, c.requests.size)
                assertTrue(c.old.state.value.currentSources.isEmpty()); assertTrue(c.unrelated.isActive)
            } finally { hold.release.countDown() }
        } }
    }

    @Test fun `AUTH-1 check wins the guard before the commit and the admitted start is then cancelled`() = runBlocking {
        for (operation in listOf("version", "devices")) for (cause in causes) SyntheticProfileInformation().use { f -> Chat(f).use { c ->
            val hold = Hold(); val once = AtomicBoolean()
            // Every started call is held in the interceptor until ITS OWN Call.cancel() arrived (bounded; a miss fails the call and the assertions below).
            val cancelOf = java.util.concurrent.ConcurrentHashMap<okhttp3.Call, CountDownLatch>()
            f.onCanceled = { call -> cancelOf.computeIfAbsent(call) { CountDownLatch(1) }.countDown() }
            f.beforeResponseOf = { call -> check(cancelOf.computeIfAbsent(call) { CountDownLatch(1) }.await(15, TimeUnit.SECONDS)) { "call was never cancelled" } }
            c.settings.assistantChat.probes = ChatProbes(checked = { category -> if (category == "profile-$operation-dispatch" && once.compareAndSet(false, true)) hold.waitHere() })
            val revokeStarted = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val revoke = Thread { try { revokeStarted.countDown(); c.revoke(cause) } catch (e: Throwable) { failure.set(e) } }
            try {
                c.send(); hold.entered("$cause/$operation")
                revoke.start(); assertTrue(revokeStarted.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (revoke.isAlive && revoke.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals("The commit must wait for the start check's guard", Thread.State.BLOCKED, revoke.state)
                hold.release.countDown(); revoke.join(2000); assertFalse(revoke.isAlive); failure.get()?.let { throw it }
                // Variant 1: an admitted start may begin before the commit's cancel arrives or never begin; if it began, that exact call is cancelled.
                c.idle()
                assertTrue("$cause/$operation: every started call was cancelled: ${f.enqueues} vs ${f.canceled}", f.canceled.containsAll(f.enqueues))
                assertTrue(c.old.state.value.turns.isEmpty()); assertTrue(c.old.state.value.currentSources.isEmpty())
                assertTrue(c.unrelated.isActive)
            } finally { hold.release.countDown(); revoke.join(2000) }
        } }
    }

    @Test fun `OWN-EXIT-1 interceptor and body exit retain shared slot across all revocations and reopen`() = runBlocking {
        for (stage in listOf("interceptor", "body")) for (cause in causes) SyntheticProfileInformation().use { f -> Chat(f).use { c ->
            val hold = Hold(4)
            val canceled = CountDownLatch(4)
            f.onCanceled = { canceled.countDown() }
            when (stage) {
                "interceptor" -> f.beforeResponse = hold::waitHere
                else -> f.beforeBodyRead = hold::waitHere
            }
            try {
                c.send(); hold.entered("$stage/$cause")
                assertEquals(setOf("work.example/devices", "work.example/version", "home.example/devices", "home.example/version"), f.enqueues.toSet())
                c.revoke(cause)
                assertTrue("$stage/$cause Stop reached all four exact calls", canceled.await(2, TimeUnit.SECONDS))
                assertTrue("$stage/$cause outstanding exchange must own global slot", c.settings.assistantChat.busy.value)
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
            f.beforeResponse = hold::waitHere
            try {
                c.send(); hold.entered("tool-only")
                c.settings.setAssistantTool(AssistantToolId.PROFILE_INFORMATION, false); c.old.refresh()
                assertTrue(c.settings.assistantChat.busy.value)
                hold.release.countDown(); c.idle()
                assertEquals("Old information answer", c.old.state.value.turns.single().answer)
                assertEquals(2, c.requests.size); assertEquals(4, f.enqueues.size); assertTrue(f.canceled.isEmpty())
                assertEquals(listOf("profile_information"), c.requests.last().tools.map { it.name })
            } finally { hold.release.countDown() }
        } }
    }

    @Test fun `ambient categories are the profile-dispatch strings and tool-start`() = runBlocking {
        SyntheticProfileInformation().use { f -> Chat(f).use { c ->
            val categories = java.util.concurrent.CopyOnWriteArrayList<String>()
            c.settings.assistantChat.probes = ChatProbes(before = { category -> categories += category })
            c.send(); c.idle()
            assertEquals("Old information answer", c.old.state.value.turns.single().answer)
            assertEquals(1, categories.count { it == "tool-start" })
            assertEquals(2, categories.count { it == "profile-version-dispatch" }); assertEquals(2, categories.count { it == "profile-devices-dispatch" })
            assertEquals(emptyList<String>(), categories.filter { it == "version" || it == "devices" })
        } }
    }
}
