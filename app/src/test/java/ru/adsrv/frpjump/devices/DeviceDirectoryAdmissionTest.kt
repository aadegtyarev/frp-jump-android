package ru.adsrv.frpjump.devices

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.SideEffectAdmission
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation

/**
 * The ambient start check must reach every `GET /version` and `GET /devices` of `pull` by ordinary context inheritance:
 * no `CoroutineScope(...)` and no `background` hop may sit between the assistant tool and `FrpJumpApi`.
 */
class DeviceDirectoryAdmissionTest {
    private fun admission(categories: MutableList<String>, allow: (String) -> Boolean = { true }) = SideEffectAdmission { categories += it; allow(it) }

    @Test fun `ambient admission reaches version and devices of every profile through refreshAll`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            val categories = CopyOnWriteArrayList<String>()
            withContext(Dispatchers.IO + admission(categories)) { f.information.refreshAll() }
            assertEquals(listOf("devices", "devices", "version", "version"), categories.sorted())
            assertEquals(2, f.deviceRequests.get()); assertEquals(2, f.versionRequests.get())
            assertEquals(setOf("work.example/devices", "work.example/version", "home.example/devices", "home.example/version"), f.enqueues.toSet())
        }
    }

    @Test fun `refusing admission sends nothing and a cancellation is never reported as a failed fetch`() = runBlocking {
        for (refused in listOf(setOf("version", "devices"), setOf("devices"))) SyntheticProfileInformation().use { f ->
            val categories = CopyOnWriteArrayList<String>()
            try {
                withContext(Dispatchers.IO + admission(categories) { it !in refused }) { f.information.refreshAll() }
                fail("$refused: refusal must cancel")
            } catch (cancelled: CancellationException) { assertFalse(cancelled is TimeoutCancellationException) }
            assertEquals("$refused: a refused request creates no call", emptyList<String>(), f.enqueues.filter { path -> refused.any { path.endsWith("/$it") } })
            assertEquals(0, f.deviceRequests.get())
            assertTrue("$refused: refusal is not an error of the list", f.runtimes.values.all { it.devices.state.value.error == null && !it.devices.state.value.stale })
        }
    }

    @Test fun `refusing only version leaves the list fetch to its own check`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            val categories = CopyOnWriteArrayList<String>()
            withContext(Dispatchers.IO + admission(categories) { it != "version" }) { f.information.refreshAll() }
            assertEquals(0, f.versionRequests.get()); assertEquals(2, f.deviceRequests.get())
            assertEquals(listOf("devices", "devices", "version", "version"), categories.sorted())
        }
    }

    @Test fun `Stop reaches the exact version and devices calls of every profile through the pull`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            val entered = CountDownLatch(4); val release = CountDownLatch(1); val canceled = CountDownLatch(4)
            f.beforeResponse = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.onCanceled = { canceled.countDown() }
            val job = launch(Dispatchers.IO + admission(CopyOnWriteArrayList())) { f.information.refreshAll() }
            try {
                assertTrue("all four exchanges entered", entered.await(3, TimeUnit.SECONDS))
                job.cancel()
                assertTrue("all four exact calls were cancelled", canceled.await(3, TimeUnit.SECONDS))
                assertNull("the exits are awaited", withTimeoutOrNull(100) { job.join() })
            } finally { release.countDown() }
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(f.runtimes.values.all { it.devices.state.value.error == null })
        }
    }

    @Test fun `pull without an ambient element is ordinary`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            f.information.refreshAll()
            assertEquals(2, f.deviceRequests.get()); assertEquals(2, f.versionRequests.get())
            assertTrue(f.runtimes.values.all { it.devices.state.value.error == null && !it.devices.state.value.stale })
        }
    }
}
