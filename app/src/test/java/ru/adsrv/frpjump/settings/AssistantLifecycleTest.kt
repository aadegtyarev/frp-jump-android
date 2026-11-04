package ru.adsrv.frpjump.settings

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.bindAssistantLifecycle
import ru.adsrv.frpjump.store.TextStorage

class AssistantLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    private class FileStorage(private val file: File) : TextStorage {
        var failWrites = false
        override fun read() = file.readText().takeIf { it.isNotEmpty() }
        override fun write(text: String) {
            if (failWrites) throw IOException("Synthetic persistence failure")
            file.writeText(text)
        }
        override fun delete() { file.delete() }
    }

    @Test fun `committed off on during cooperative model cannot revive run`() = committedDisable(false, false)
    @Test fun `committed off on during noncooperative model cannot revive run`() = committedDisable(false, true)
    @Test fun `committed off on during cooperative tool cannot revive run`() = committedDisable(true, false)
    @Test fun `committed off on during noncooperative tool cannot revive run`() = committedDisable(true, true)

    private fun committedDisable(inTool: Boolean, noncooperative: Boolean) = runBlocking {
        val file = FileStorage(temporary.newFile())
        val settings = SettingsStore(file)
        settings.update { it.copy(assistantEnabled = true) }
        val bindingScope = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val module = bindAssistantLifecycle(settings, bindingScope)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val unrelatedRelease = CompletableDeferred<Unit>()
        val unrelated = async { unrelatedRelease.await(); "healthy" }
        val args = JsonObject(emptyMap())
        var requests = 0
        var tools = 0
        val events = mutableListOf<AgentEvent>()
        suspend fun pause() {
            suspend fun waitHere() { entered.complete(Unit); release.await() }
            if (noncooperative) withContext(NonCancellable) { waitHere() } else waitHere()
        }
        val tool = object : AgentTool {
            override val spec = ToolSpec("fake", "Synthetic fixture", args)
            override suspend fun execute(arguments: JsonObject): ToolOutput {
                tools++
                if (inTool) pause()
                return ToolOutput("done")
            }
        }
        val run = async(start = CoroutineStart.UNDISPATCHED) {
            module.run(AgentProvider {
                requests++
                if (!inTool) pause()
                if (requests == 1) AgentReply(calls = listOf(ToolCall("one", "fake", args), ToolCall("two", "fake", args)))
                else AgentReply("done")
            }, listOf(tool), "", "user", onEvent = events::add)
        }
        try {
            withTimeout(2000) { entered.await() }
            val eventsBefore = events.size
            // Same dispatcher is occupied here: neither binding collector nor a VM continuation can run.
            // These are concurrent callers' committed writes, NOT rapid taps through the UI busy guard.
            repeat(2) {
                settings.update { it.copy(assistantEnabled = false) }
                assertFalse(SettingsStore(file).settings.value.assistantEnabled)
                settings.update { it.copy(assistantEnabled = true) }
                assertTrue(SettingsStore(file).settings.value.assistantEnabled)
            }
            assertEquals(2L, settings.assistantAvailability.disableVersion)
            assertEquals(AgentFinish.BUSY, module.run(AgentProvider { error("overlap") }, emptyList(), "", "new").finish)
            yield() // Give the real binding its conflated, latest enabled value.
            if (!noncooperative) {
                withTimeout(2000) { run.join() }
                assertTrue("Committed disable must cancel cooperative work", run.isCancelled)
            } else {
                assertTrue("Stopping noncooperative work retains its slot", module.isRunning)
                assertEquals(AgentFinish.BUSY, module.run(AgentProvider { error("overlap") }, emptyList(), "", "new").finish)
            }
            release.complete(Unit)
            var cancelled = false
            try { withTimeout(2000) { run.await() } } catch (_: CancellationException) { cancelled = true }
            assertTrue("Committed disable must irreversibly invalidate the old run", cancelled)
            assertEquals(1, requests)
            assertEquals(if (inTool) 1 else 0, tools)
            assertEquals(eventsBefore, events.size)
            assertFalse(module.isRunning)
            unrelatedRelease.complete(Unit)
            assertEquals("healthy", unrelated.await())
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(AgentFinish.COMPLETE, module.run(AgentProvider { AgentReply("fresh") }, emptyList(), "", "new").finish)
        } finally {
            release.complete(Unit)
            run.cancelAndJoin()
            unrelated.cancelAndJoin()
            bindingScope.cancel()
        }
    }

    @Test fun `model return before observer dispatch still checks the committed disable edge`() = returnsBeforeObserver(false)
    @Test fun `tool return before observer dispatch still checks the committed disable edge`() = returnsBeforeObserver(true)

    private fun returnsBeforeObserver(inTool: Boolean) = runBlocking {
        val settings = SettingsStore(FileStorage(temporary.newFile()))
        settings.update { it.copy(assistantEnabled = true) }
        val scope = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val module = bindAssistantLifecycle(settings, scope)
        val args = JsonObject(emptyMap())
        var tools = 0
        var requests = 0
        fun offOn() {
            settings.update { it.copy(assistantEnabled = false) }
            settings.update { it.copy(assistantEnabled = true) }
        }
        try {
            val tool = object : AgentTool {
                override val spec = ToolSpec("fake", "Synthetic", args)
                override suspend fun execute(arguments: JsonObject): ToolOutput {
                    tools++
                    offOn()
                    return ToolOutput("done")
                }
            }
            try {
                module.run(AgentProvider {
                    requests++
                    if (!inTool) offOn()
                    AgentReply(calls = listOf(ToolCall("one", "fake", args), ToolCall("two", "fake", args)))
                }, listOf(tool), "", "user")
                fail("Expected invalidation before observer dispatch")
            } catch (_: CancellationException) { }
            assertEquals(1, requests)
            assertEquals(if (inTool) 1 else 0, tools)
        } finally { scope.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `unconfined cancellation callbacks run outside settings monitor`() = runBlocking {
        val settings = SettingsStore(FileStorage(temporary.newFile()))
        settings.update { it.copy(assistantEnabled = true) }
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val module = bindAssistantLifecycle(settings, scope)
        var cancelledUnderMonitor: Boolean? = null
        val run = async(Dispatchers.Unconfined) {
            module.run(AgentProvider {
                suspendCancellableCoroutine<AgentReply> { continuation ->
                    continuation.invokeOnCancellation { cancelledUnderMonitor = Thread.holdsLock(settings) }
                }
            }, emptyList(), "", "user")
        }
        try {
            settings.update { it.copy(assistantEnabled = false) }
            try { run.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            assertEquals(false, cancelledUnderMonitor)
        } finally { run.cancelAndJoin(); scope.cancel() }
    }

    @Test fun `binding stays default off and blocks admission before observer dispatch`() = runBlocking {
        val settings = SettingsStore(FileStorage(temporary.newFile()))
        val scope = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val module = bindAssistantLifecycle(settings, scope)
        val provider = AgentProvider { AgentReply("done") }
        try {
            assertFalse(module.isEnabled)
            assertEquals(AgentFinish.DISABLED, module.run(provider, emptyList(), "", "user").finish)
            settings.update { it.copy(assistantEnabled = true) }
            assertEquals(AgentFinish.COMPLETE, module.run(provider, emptyList(), "", "user").finish)
            settings.update { it.copy(assistantEnabled = false) }
            assertFalse(module.isEnabled)
            assertEquals(AgentFinish.DISABLED, module.run(provider, emptyList(), "", "user").finish)
        } finally { scope.cancel() }
    }

    @Test fun `delayed disable notification cannot cancel a fresh enabled run`() = runBlocking {
        val settings = SettingsStore(FileStorage(temporary.newFile()))
        settings.update { it.copy(assistantEnabled = true) }
        val scope = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val module = bindAssistantLifecycle(settings, scope)
        val release = CompletableDeferred<Unit>()
        settings.update { it.copy(assistantEnabled = false) }
        settings.update { it.copy(assistantEnabled = true) }
        val run = async(start = CoroutineStart.UNDISPATCHED) {
            module.run(AgentProvider { release.await(); AgentReply("done") }, emptyList(), "", "fresh")
        }
        try {
            yield()
            assertTrue(run.isActive)
            release.complete(Unit)
            assertEquals(AgentFinish.COMPLETE, run.await().finish)
        } finally { release.complete(Unit); run.cancelAndJoin(); scope.cancel() }
    }

    @Test fun `failed disable persistence does not cancel or invalidate suspended run`() = runBlocking {
        val file = FileStorage(temporary.newFile())
        val settings = SettingsStore(file)
        settings.update { it.copy(assistantEnabled = true) }
        val scope = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val module = bindAssistantLifecycle(settings, scope)
        val release = CompletableDeferred<Unit>()
        val run = async(start = CoroutineStart.UNDISPATCHED) {
            module.run(AgentProvider { release.await(); AgentReply("done") }, emptyList(), "", "user")
        }
        try {
            file.failWrites = true
            assertThrows(IOException::class.java) { settings.update { it.copy(assistantEnabled = false) } }
            assertTrue(SettingsStore(file).settings.value.assistantEnabled)
            assertEquals(0L, settings.assistantAvailability.disableVersion)
            yield()
            assertTrue(run.isActive)
            release.complete(Unit)
            assertEquals(AgentFinish.COMPLETE, run.await().finish)
        } finally {
            release.complete(Unit)
            run.cancelAndJoin()
            scope.cancel()
        }
    }
}
