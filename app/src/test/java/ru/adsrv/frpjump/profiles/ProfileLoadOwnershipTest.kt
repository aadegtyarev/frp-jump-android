package ru.adsrv.frpjump.profiles

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ssh.MemoryStorage

/** Actual Profiles/ProfileRuntime over synthetic files. HTTP interceptor prevents every socket. */
class ProfileLoadOwnershipTest {
    private val dir = scratchDir()
    private val files = FileStorages()
    private val keys = FakeKeystore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val made = mutableListOf<ProfileRuntime>()
    private var nextId = 0
    private val store = ProfileStore(dir, keys::box, files::storage, newId = { "synthetic-${++nextId}" })
    private val record = RestoreRecord(MemoryStorage()).apply { load() }
    private var failingConnections: String? = null
    private var failingFile = ProfileFiles.CONNECTIONS
    private val settings = MutableStateFlow(AppSettings())
    private val shared = ProfileShared(
        api = FrpJumpApi(OkHttpClient.Builder().addInterceptor { throw IOException("Synthetic offline: no socket") }.build()),
        localPorts = LocalPortStore(files.storage(File(dir, "local_ports.json"))),
        settings = settings,
        visible = MutableStateFlow(false),
        frpcBinary = File(dir, "no-frpc"),
        frpcRoot = File(dir, "frpc"),
        startService = { error("No service in a profile-load ownership fixture") },
        scope = scope,
        storageFor = { file ->
            if (file.parentFile?.name == failingConnections && file.name == failingFile) {
                throw IOException("Synthetic connections preparation failure")
            }
            files.storage(file)
        },
        directKept = DirectKeptStore(MemoryStorage()),
        network = MutableStateFlow(null),
        restoreRecord = record,
    )

    @After
    fun cleanUp() {
        made.forEach { it.close() }
        scope.cancel()
        runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]!!.join() } }
        dir.deleteRecursively()
    }

    @Test
    fun `second factory failure leaves no unpublished runtime job alive`() = runBlocking {
        val (first, second) = seed()
        val profiles = profiles { if (it.id == second.id) throw IOException("Synthetic factory failure") }
        assertFalse(profiles.load())
        assertEquals(listOf(first.id), made.map { it.id })
        assertTrue(profiles.state.value.runtimes.isEmpty())
        assertEquals("Failed batch must close its first unpublished runtime", 0, activeRuntimeJobs())
        assertTrue("Its tunnel manager's idle observers close too", tunnelJob(made.single()).isCancelled)
    }

    @Test
    fun `retry after a failed batch owns exactly one runtime per profile`() = runBlocking {
        val (_, second) = seed()
        var fail = true
        val profiles = profiles { if (fail && it.id == second.id) throw IOException("Synthetic factory failure") }
        assertFalse(profiles.load())
        fail = false
        assertTrue(profiles.load())
        assertEquals(2, profiles.state.value.runtimes.size)
        assertEquals("A retry must not leave the first attempt polling beside the new one", 2, activeRuntimeJobs())
        assertTrue(tunnelJob(made.first()).isCancelled)
        profiles.state.value.runtimes.values.forEach { assertTrue(tunnelJob(it).isActive) }
    }

    @Test
    fun `failure inside a runtime constructor leaves no child scope behind`() = runBlocking {
        val (_, second) = seed()
        failingConnections = second.id
        val profiles = profiles()
        assertFalse(profiles.load())
        assertTrue(profiles.state.value.runtimes.isEmpty())
        assertEquals("Neither prepared nor partially constructed runtime may retain a job", 0, activeRuntimeJobs())
    }

    @Test
    fun `cancelled batch releases prepared owners without reporting a storage failure`() = runBlocking {
        val (_, second) = seed()
        val profiles = profiles { if (it.id == second.id) throw CancellationException("Synthetic cancellation") }
        val thrown = runCatching { profiles.load() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertNull(profiles.state.value.failure)
        assertTrue(profiles.state.value.runtimes.isEmpty())
        assertEquals("Cancellation must clean the unpublished first runtime too", 0, activeRuntimeJobs())
    }

    @Test
    fun `prepared runtime does not start background jobs before the batch is owned`() = runBlocking {
        val (_, second) = seed()
        val profiles = profiles { p ->
            if (p.id == second.id) {
                val firstScope = ProfileRuntime::class.java.getDeclaredField("scope")
                    .apply { isAccessible = true }.get(made.single()) as? CoroutineScope
                val running = firstScope?.coroutineContext?.get(Job)?.children?.count { it.isActive } ?: 0
                assertEquals("Runtime startup work must wait until every runtime is prepared and published", 0, running)
                // Its separately scoped tunnel manager may observe empty state, but cannot poll/restore.
                assertTrue(tunnelJob(made.single()).isActive)
            }
        }
        assertTrue(profiles.load())
        assertEquals(2, activeRuntimeJobs())
    }

    @Test
    fun `duplicate ids in a damaged index cannot overwrite a prepared owner`() = runBlocking {
        val p = store.add(credentials("first.invalid"))
        File(dir, ProfileFiles.INDEX).writeText(WireJson.encodeToString(ProfileIndex(listOf(p, p), p.id)))
        val profiles = profiles()
        assertFalse("A duplicate id must not hide an already prepared candidate", profiles.load())
        assertTrue(profiles.state.value.runtimes.isEmpty())
        assertEquals(0, activeRuntimeJobs())
        assertTrue(store.credentials(p).state() is ru.adsrv.frpjump.store.StoredEnrollment.Enrolled)
    }

    @Test
    fun `closing a prepared runtime is final and cannot activate it later`() {
        val p = store.add(credentials("first.invalid"))
        val runtime = ProfileRuntime(p.id, store.credentials(p), store.dir(p.id), MutableStateFlow(p.id), shared)
            .also { made += it }
        assertEquals(0, childJobs(runtime))
        runtime.close()
        runtime.activate()
        runtime.close()
        assertEquals(0, activeRuntimeJobs())
        assertEquals(0, childJobs(runtime))
    }

    @Test
    fun `repeated publication does not duplicate background collectors`() = runBlocking {
        val (first, second) = seed()
        val profiles = profiles()
        assertTrue(profiles.load())
        val before = made.associate { it.id to childJobs(it) }
        repeat(3) {
            profiles.setCurrent(if (it % 2 == 0) first.id else second.id)
            assertTrue(profiles.update(first.id, "Synthetic name $it", "blue"))
        }
        assertEquals(2, activeRuntimeJobs())
        made.forEach { assertTrue("Publication reuses collectors", childJobs(it) <= before.getValue(it.id)) }
    }

    @Test
    fun `range changed during preparation is present on every runtime when load returns`() = runBlocking {
        val (_, second) = seed()
        val changed = settings.value.copy(localPortFirst = 30100, localPortLast = 30200)
        val profiles = profiles { if (it.id == second.id) settings.value = changed }
        assertTrue(profiles.load())
        made.forEach { assertEquals(changed.localPorts, it.tunnels.portRange) }
    }

    @Test
    fun `all fallible storage wrappers are prepared before allocating a child scope`() = runBlocking {
        val (first, _) = seed()
        failingConnections = first.id
        for (name in listOf(ProfileFiles.HOST_KEYS, ProfileFiles.CONNECTIONS, ProfileFiles.DEVICE_LIST, ProfileFiles.SERVER_FEATURES)) {
            failingFile = name
            val profiles = profiles()
            assertFalse(profiles.load())
            assertEquals("No job allocated before $name is prepared", 0, activeRuntimeJobs())
        }
    }

    @Test
    fun `failed activation preparation keeps credentials ports and restore intent`() = runBlocking {
        val (first, second) = seed()
        record.set(first.id, 1)
        val before = shared.localPorts.load()
        val profiles = profiles { if (it.id == second.id) throw IOException("Synthetic preparation failure") }
        assertFalse(profiles.load())
        assertEquals(mapOf(first.id to 1), record.profiles.value)
        assertEquals(before, shared.localPorts.load())
        assertTrue(store.credentials(first).state() is ru.adsrv.frpjump.store.StoredEnrollment.Enrolled)
        assertTrue(store.credentials(second).state() is ru.adsrv.frpjump.store.StoredEnrollment.Enrolled)
        assertEquals(0, activeRuntimeJobs())
    }

    @Test
    fun `successful batch publishes every runtime once and a second load reuses them`() = runBlocking {
        seed()
        val profiles = profiles()
        assertTrue(profiles.load())
        val state = profiles.state.value
        assertEquals(2, state.runtimes.size)
        assertEquals(2, activeRuntimeJobs())
        assertTrue(profiles.load())
        assertSame(state, profiles.state.value)
        assertEquals(2, made.size)
    }

    private fun seed(): Pair<Profile, Profile> =
        store.add(credentials("first.invalid")) to store.add(credentials("second.invalid", "second"))

    private fun profiles(before: (Profile) -> Unit = {}): Profiles = Profiles(store, record.profiles) { p, s, current ->
        before(p)
        ProfileRuntime(p.id, s.credentials(p), s.dir(p.id), current, shared).also { made += it }
    }

    private fun activeRuntimeJobs(): Int = scope.coroutineContext[Job]!!.children.count { it.isActive }

    private fun tunnelJob(runtime: ProfileRuntime): Job {
        val owner = runtime.tunnels
        val child = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(owner) as CoroutineScope
        return child.coroutineContext[Job]!!
    }

    private fun childJobs(runtime: ProfileRuntime): Int {
        val child = ProfileRuntime::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(runtime) as CoroutineScope
        return child.coroutineContext[Job]!!.children.count { it.isActive }
    }
}
