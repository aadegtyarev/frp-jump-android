package ru.adsrv.frpjump.ui.settings

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.TextStorage

class AssistantToolsBindingTest {
    private class Queue : CoroutineDispatcher() {
        val work = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.add(block) }
        fun next() = checkNotNull(work.poll()).run()
        fun drain() { while (work.isNotEmpty()) next() }
    }
    private class Memory : TextStorage {
        @Volatile var text: String? = null
        var fail = false
        var writes = 0
        var beforeWrite: (() -> Unit)? = null
        override fun read() = text
        override fun write(text: String) { beforeWrite?.invoke(); if (fail) error("synthetic private failure"); writes++; this.text = text }
        override fun delete() { text = null }
    }
    private class Fixture : AutoCloseable {
        val file = Memory()
        val store = SettingsStore(file)
        val main = Queue(); val io = Queue()
        val scope = CoroutineScope(SupervisorJob() + main)
        val binding = AssistantToolsBinding(store, scope, io)
        init { main.drain() }
        fun change(id: String, value: Boolean) { binding.change(AssistantToolId.fromWire(id)!!, value); main.drain() }
        fun finish() { io.drain(); main.drain() }
        fun canonical() {
            assertEquals(store.assistantAvailability.tools, binding.state.value.committed)
            assertEquals(store.assistantAvailability.tools, SettingsStore(file).assistantAvailability.tools)
        }
        override fun close() { scope.cancel(); main.drain() }
    }
    private val ids = listOf("web_search", "read_page", "inspect_image", "profile_information", "ssh_command")
    private fun AssistantTools.checked(id: String) = when (id) {
        "web_search" -> search; "read_page" -> readPage; "inspect_image" -> inspectImage; "profile_information" -> profileInformation; else -> sshCommand
    }

    @Test fun `each tool both directions keeps old checked pending then acknowledges actual commit atomically`() {
        Fixture().use { f ->
            val observed = mutableListOf<AssistantToolsUiState>()
            f.scope.launch { f.binding.state.collect { observed += it } }; f.main.drain()
            for (id in ids) for (value in listOf(true, false)) {
                val before = f.binding.state.value.committed
                f.change(id, value)
                assertEquals(before, f.binding.state.value.committed)
                assertEquals(setOf(id), f.binding.state.value.pending)
                assertEquals(before, f.store.assistantAvailability.tools)
                f.finish(); f.canonical()
                assertEquals(value, f.binding.state.value.committed.checked(id))
                assertTrue(f.binding.state.value.pending.isEmpty()); assertTrue(f.binding.state.value.failed.isEmpty())
                assertEquals(value, observed.last().committed.checked(id))
                assertTrue(observed.last().pending.isEmpty())
            }
            assertEquals(ids.size * 2, f.file.writes)
        }
    }

    @Test fun `same ID duplicate is dropped no queue while different IDs commit in reverse order`() {
        Fixture().use { f ->
            f.change(ids[0], true); f.change(ids[0], false)
            ids.drop(1).forEach { f.change(it, true) }
            assertEquals(ids.toSet(), f.binding.state.value.pending)
            assertEquals(ids.size, f.io.work.size)
            val jobs = f.io.work.toList(); f.io.work.clear()
            jobs.reversed().forEach { it.run(); f.main.drain(); f.canonical() }
            assertEquals(AssistantTools(true, true, true, true, true), f.binding.state.value.committed)
            assertTrue(f.binding.state.value.pending.isEmpty()); assertEquals(ids.size, f.file.writes)
        }
    }

    @Test fun `failed save presents canonical and inline failure then explicit retry and no-op succeed`() {
        Fixture().use { f ->
            for (id in ids) {
                f.file.fail = true; f.change(id, true); f.finish(); f.canonical()
                assertFalse(f.binding.state.value.committed.checked(id))
                assertEquals(setOf(id), f.binding.state.value.failed); assertTrue(f.binding.state.value.pending.isEmpty())
                f.file.fail = false; f.change(id, true)
                assertTrue(f.binding.state.value.failed.isEmpty()); f.finish(); f.canonical()
                val writes = f.file.writes
                f.change(id, true); f.finish(); f.canonical()
                assertEquals(writes, f.file.writes); assertTrue(f.binding.state.value.failed.isEmpty())
            }
        }
    }

    @Test fun `ack reads latest canonical not requested or captured notification and preserves unrelated preferences`() {
        Fixture().use { f ->
            f.change("web_search", true)
            f.io.next() // Storage committed; notification and acknowledgement are still queued on Main.
            assertTrue(f.store.assistantAvailability.tools.search)
            f.store.update { it.copy(assistantSearch = false, assistantReadPage = true, direct = false) }
            f.main.drain(); f.canonical()
            assertEquals(AssistantTools(false, true, false), f.binding.state.value.committed)
            assertTrue(f.binding.state.value.pending.isEmpty()); assertFalse(SettingsStore(f.file).settings.value.direct)
            // Another external change after an acknowledged result also refreshes without local action.
            f.store.setAssistantTool(AssistantToolId.INSPECT_IMAGE, true); f.main.drain(); f.canonical()
            assertEquals(AssistantTools(false, true, true), f.binding.state.value.committed)
        }
    }

    @Test fun `notification refresh preserves sibling pending and failure without rolling back another writer`() {
        Fixture().use { f ->
            f.file.fail = true; f.change("web_search", true); f.finish()
            f.file.fail = false; f.change("read_page", true)
            f.store.setAssistantTool(AssistantToolId.INSPECT_IMAGE, true); f.main.drain()
            assertEquals(setOf("web_search"), f.binding.state.value.failed)
            assertEquals(setOf("read_page"), f.binding.state.value.pending)
            assertEquals(AssistantTools(false, false, true), f.binding.state.value.committed)
            f.finish(); f.canonical()
            assertEquals(AssistantTools(false, true, true), f.binding.state.value.committed)
            assertEquals(setOf("web_search"), f.binding.state.value.failed)
        }
    }

    @Test fun `failed operation acknowledges later external commit without pretending rollback or success`() {
        Fixture().use { f ->
            f.file.fail = true; f.change("web_search", true); f.io.next()
            f.file.fail = false; f.store.setAssistantTool(AssistantToolId.WEB_SEARCH, true)
            f.main.drain(); f.canonical()
            assertTrue(f.binding.state.value.committed.search)
            assertEquals(setOf("web_search"), f.binding.state.value.failed)
            assertTrue(f.binding.state.value.pending.isEmpty())
        }
    }

    @Test fun `initial and reopened owners read canonical even when old notification has not been consumed`() {
        Fixture().use { f ->
            f.store.setAssistantTool(AssistantToolId.WEB_SEARCH, true)
            val newer = AssistantToolsBinding(f.store, f.scope, f.io)
            assertEquals(AssistantTools(true), newer.state.value.committed)
            f.store.setAssistantTool(AssistantToolId.WEB_SEARCH, false); f.main.drain()
            assertEquals(AssistantTools(), newer.state.value.committed)
            f.canonical()
        }
    }

    @Test fun `cleared scope cannot publish late blocking commit or affect replacement owner and cancellation is not failure`() {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.file.beforeWrite = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            f.change("web_search", true)
            val oldState = f.binding.state.value
            val writer = Thread { f.io.next() }
            writer.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val newScope = CoroutineScope(SupervisorJob() + f.main)
            try {
                f.scope.cancel(); f.main.drain()
                val newer = AssistantToolsBinding(f.store, newScope, f.io); f.main.drain()
                assertFalse(newer.state.value.committed.search)
                release.countDown(); writer.join(2000); assertFalse(writer.isAlive); f.main.drain()
                assertEquals(oldState, f.binding.state.value)
                assertTrue(f.binding.state.value.failed.isEmpty())
                assertTrue(newer.state.value.committed.search); assertTrue(newer.state.value.pending.isEmpty())
                assertTrue(SettingsStore(f.file).assistantAvailability.tools.search)
                f.binding.change(AssistantToolId.READ_PAGE, true); f.main.drain()
                assertEquals(oldState, f.binding.state.value); assertTrue(f.io.work.isEmpty())
            } finally { release.countDown(); writer.join(2000); newScope.cancel(); f.main.drain() }
        }
    }
}
