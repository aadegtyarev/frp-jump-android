package ru.adsrv.frpjump.profiles

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.StoredEnrollment

/** Actual durable stores and runtimes; interceptor rejects requests before any socket. */
class SavedProfileRetryTest {
    private val dir = scratchDir()
    private val files = FileStorages()
    private val keys = FakeKeystore()
    private var ids = 0
    private val store = ProfileStore(dir, keys::box, files::storage, newId = { "saved-${++ids}" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val made = mutableListOf<ProfileRuntime>()
    private val record = RestoreRecord(MemoryStorage()).apply { load() }
    private var failSaved = true
    private var failingId = "saved-2"
    private val shared = ProfileShared(
        api = FrpJumpApi(OkHttpClient.Builder().addInterceptor { throw IOException("Synthetic offline: no socket") }.build()),
        localPorts = LocalPortStore(files.storage(File(dir, "local_ports.json"))),
        settings = MutableStateFlow(AppSettings()), visible = MutableStateFlow(false),
        frpcBinary = File(dir, "no-frpc"), frpcRoot = File(dir, "frpc"),
        startService = { error("No service in saved connection fixture") }, scope = scope,
        storageFor = files::storage, directKept = DirectKeptStore(MemoryStorage()),
        network = MutableStateFlow(null), restoreRecord = record,
    )

    @After
    fun cleanUp() {
        made.forEach { it.close() }
        scope.cancel()
        runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]!!.join() } }
        dir.deleteRecursively()
    }

    @Test
    fun `saved connection remains visible after its runtime factory fails`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        val saved = failNew(profiles)
        assertNotNull("Saved identity must remain available for manual activation", profiles.state.value.index.find(saved.id))
        assertSame(working, profiles.current)
        assertNull(profiles.runtime(saved.id))
        assertEquals(2, store.index().profiles.size)
        assertTrue(store.credentials(saved).state() is StoredEnrollment.Enrolled)
    }

    @Test
    fun `manual selection activates saved identity without creating another enrollment`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        val saved = failNew(profiles)
        val sealedBefore = File(store.dir(saved.id), ProfileFiles.ENROLLMENT).readText()
        failSaved = false
        profiles.setCurrent(saved.id)
        assertNotNull("Manual retry must open the previously saved connection", profiles.runtime(saved.id))
        val activated = profiles.runtime(saved.id)
        profiles.setCurrent(saved.id)
        assertSame(activated, profiles.runtime(saved.id))
        assertEquals(sealedBefore, File(store.dir(saved.id), ProfileFiles.ENROLLMENT).readText())
        assertEquals(saved.id, profiles.current?.id)
        assertSame(working, profiles.runtime(working.id))
        assertEquals(2, ids)
        assertEquals(2, store.index().profiles.size)
        assertEquals(credentials("second.invalid", "second"), store.credentials(saved).load())
    }

    @Test
    fun `repeated activation failure preserves working selection and saved keys`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        val saved = failNew(profiles)
        val failure = runCatching { profiles.setCurrent(saved.id) }.exceptionOrNull()
        assertNotNull("Retry must report activation failure rather than silently ignore the saved id", failure)
        assertSame(working, profiles.current)
        assertEquals(working.id, store.index().current)
        assertNotNull(profiles.state.value.index.find(saved.id))
        assertTrue(store.credentials(saved).state() is StoredEnrollment.Enrolled)
        assertEquals(2, ids)
    }

    @Test
    fun `restart keeps failed new connection available without automatically starting it`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        val saved = failNew(profiles)
        made.forEach { it.close() }
        val restarted = profiles()
        assertTrue("A manually pending new connection must not prevent loading the working one", restarted.load())
        assertEquals(working.id, restarted.current?.id)
        assertNotNull(restarted.state.value.index.find(saved.id))
        assertNull(restarted.runtime(saved.id))
        assertEquals(2, store.index().profiles.size)
        failSaved = false
        restarted.setCurrent(saved.id)
        assertEquals(saved.id, restarted.current?.id)
        assertEquals(2, ids)
    }

    @Test
    fun `readiness write failure closes the candidate and leaves the saved entry pending`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        val saved = failNew(profiles)
        failSaved = false
        files.crashOn = ProfileFiles.INDEX
        val failure = runCatching { profiles.setCurrent(saved.id) }.exceptionOrNull()
        assertTrue(failure is SavedProfileActivationException)
        assertTrue(store.index().find(saved.id)!!.activationPending)
        assertSame(working, profiles.current)
        assertNull(profiles.runtime(saved.id))
        assertEquals(1, scope.coroutineContext[Job]!!.children.count { it.isActive })
        assertEquals(2, ids)
    }

    @Test
    fun `first saved connection can be selected again even though its id is already current`() = runBlocking {
        failingId = "saved-1"
        val profiles = profiles()
        assertTrue(profiles.load())
        val failure = runCatching { profiles.enroll(credentials("first.invalid")) }.exceptionOrNull()
        assertTrue(failure is SavedProfileActivationException)
        val id = store.index().current!!
        assertEquals(id, profiles.state.value.index.current)
        assertNull(profiles.current)
        assertTrue(profiles.state.value.index.find(id)!!.activationPending)
        made.forEach { it.close() }
        val restarted = profiles()
        assertTrue(restarted.load())
        assertTrue(restarted.state.value.runtimes.isEmpty())
        failSaved = false
        restarted.setCurrent(id)
        assertEquals(id, restarted.current?.id)
        assertFalse(store.index().find(id)!!.activationPending)
        assertEquals(1, ids)
    }

    @Test
    fun `editing pending metadata neither activates it nor changes the working connection`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        val saved = failNew(profiles)
        assertTrue(profiles.update(saved.id, "Synthetic saved name", "blue"))
        assertTrue(profiles.state.value.index.find(saved.id)!!.activationPending)
        assertEquals("Synthetic saved name", store.index().find(saved.id)?.name)
        assertSame(working, profiles.current)
        assertEquals(working.id, store.index().current)
        assertNull(profiles.runtime(saved.id))
    }

    @Test
    fun `forgetting an unstarted connection removes its saved identity without stopping the working one`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        val saved = failNew(profiles)
        var unlisted = false
        profiles.forget(saved.id, keepData = false) { last -> assertFalse(last); unlisted = true }
        assertTrue(unlisted)
        assertNull(store.index().find(saved.id))
        assertNull(profiles.savedCredentials(saved.id))
        assertSame(working, profiles.current)
        assertEquals(1, scope.coroutineContext[Job]!!.children.count { it.isActive })
    }

    @Test
    fun `enrolling again after a pending connection was wiped reuses its saved identity`() = runBlocking {
        failingId = "saved-1"
        val profiles = profiles()
        assertTrue(profiles.load())
        assertTrue(runCatching { profiles.enroll(credentials("first.invalid")) }.isFailure)
        val id = store.index().current!!
        profiles.forget(id, keepData = true)
        assertEquals(1, profiles.state.value.enrollments[id])
        failSaved = false
        val runtime = profiles.enroll(credentials("third.invalid", "third"))
        assertEquals(id, runtime.id)
        assertEquals(2, profiles.state.value.enrollments[id])
        assertEquals(1, ids)
        assertEquals(credentials("third.invalid", "third"), runtime.store.load())
    }

    @Test
    fun `cosmetic name failure after filling saved credentials is not a storage failure`() = runBlocking {
        val empty = store.add(credentials("first.invalid"))
        store.credentials(empty).wipe()
        store.update(empty.id, "", "blue")
        val profiles = profiles()
        assertTrue(profiles.load())
        files.crashOn = ProfileFiles.INDEX
        val replacement = credentials("third.invalid", "third")
        val result = runCatching { profiles.enroll(replacement) }
        assertEquals("Credentials were committed before the optional name write", replacement, store.credentials(empty).load())
        assertTrue("An optional name failure must not tell the UI that saved keys were lost", result.isSuccess)
        assertEquals(empty.id, result.getOrThrow().id)
        assertEquals(1, ids)
    }

    @Test
    fun `failure before credential storage does not claim a saved connection`() = runBlocking {
        val profiles = profiles()
        val working = working(profiles)
        files.crashOn = ProfileFiles.ENROLLMENT
        assertTrue(runCatching { profiles.enroll(credentials("second.invalid", "second")) }.isFailure)
        assertEquals(listOf(working.id), store.index().profiles.map { it.id })
        assertEquals(listOf(working.id), profiles.state.value.index.profiles.map { it.id })
        assertSame(working, profiles.current)
    }

    private suspend fun working(profiles: Profiles): ProfileRuntime {
        assertTrue(profiles.load())
        return profiles.enroll(credentials("first.invalid"))
    }

    private suspend fun failNew(profiles: Profiles): Profile {
        assertTrue(runCatching { profiles.enroll(credentials("second.invalid", "second")) }.isFailure)
        val saved = store.index().profiles.single { it.id == "saved-2" }
        assertTrue("The boundary is after credential persistence", store.credentials(saved).state() is StoredEnrollment.Enrolled)
        return saved
    }

    private fun profiles() = Profiles(store, record.profiles) { p, s, current ->
        if (failSaved && p.id == failingId) throw IOException("Synthetic activation failure after storage")
        ProfileRuntime(p.id, s.credentials(p), s.dir(p.id), current, shared).also { made += it }
    }
}
