package ru.adsrv.frpjump.profiles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.StoredEnrollment
import java.io.File

/** [Profiles] over real runtimes in files, the app not on screen: nothing polls. */
class ProfilesTest {
    private val filesDir = scratchDir()
    private val files = FileStorages()
    private val keystore = FakeKeystore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ids = 0
    private val record = RestoreRecord(MemoryStorage()).apply { load() }

    private val shared = ProfileShared(
        api = FrpJumpApi(),
        localPorts = LocalPortStore(files.storage(File(filesDir, "local_ports.json"))),
        settings = MutableStateFlow(AppSettings()),
        visible = MutableStateFlow(false),
        frpcBinary = File(filesDir, "no-frpc"),
        frpcRoot = File(filesDir, "frpc"),
        startService = { false },
        scope = scope,
        storageFor = files::storage,
        directKept = DirectKeptStore(MemoryStorage()),
        network = MutableStateFlow(null),
        restoreRecord = record,
    )

    private fun store() = ProfileStore(filesDir, keystore::box, files::storage, newId = { "id${++ids}" })

    private fun profiles(store: ProfileStore = store()) = Profiles(store, record.profiles) { profile, s, currentId ->
        ProfileRuntime(profile.id, s.credentials(profile), s.dir(profile.id), currentId, shared)
    }

    @After
    fun cleanUp() {
        scope.cancel()
        filesDir.deleteRecursively()
    }

    @Test
    fun `switching shows the other profile and survives a restart, the same or an unknown id changes nothing`() = runBlocking {
        val profiles = profiles()
        assertTrue(profiles.load())
        val work = profiles.enroll(credentials("work.example"))
        val home = profiles.enroll(credentials("home.example", "phone-home"))
        assertEquals(home.id, profiles.state.value.index.current)

        profiles.setCurrent(work.id)
        assertSame(work, profiles.current)
        // The runtime hears it on its own dispatcher.
        withTimeout(5_000) { work.current.first { it } }
        val before = profiles.state.value
        profiles.setCurrent(work.id)
        profiles.setCurrent("unknown")
        assertSame(before, profiles.state.value)

        assertEquals(work.id, store().load().current)
    }

    @Test
    fun `renaming keeps the runtime, an unknown id is refused`() = runBlocking {
        val profiles = profiles()
        profiles.load()
        val work = profiles.enroll(credentials("work.example"))

        assertTrue(profiles.update(work.id, "Work", "crimson"))
        assertEquals("Work", profiles.state.value.index.find(work.id)?.name)
        assertEquals("crimson", profiles.state.value.index.find(work.id)?.color)
        assertSame(work, profiles.runtime(work.id))
        assertFalse(profiles.update("unknown", "x", "green"))
    }

    @Test
    fun `enrolling fills a shown profile without credentials, else adds one`() = runBlocking {
        val store = store()
        val empty = store.add(credentials("old.example"))
        store.credentials(empty).wipe()
        val profiles = profiles(store)
        profiles.load()

        val filled = profiles.enroll(credentials("new.example"))
        assertEquals(empty.id, filled.id)
        // What was read from its old credentials is stale now.
        assertEquals(1, profiles.state.value.enrollments[empty.id])
        assertEquals(1, profiles.state.value.index.profiles.size)
        assertTrue(filled.store.state() is StoredEnrollment.Enrolled)

        val added = profiles.enroll(credentials("other.example", "phone-2"))
        assertNull(profiles.state.value.enrollments[added.id])
        assertEquals(listOf(empty.id, added.id), profiles.state.value.index.profiles.map { it.id })
        assertEquals(added.id, profiles.state.value.index.current)
    }

    @Test
    fun `forgetting removes the profile and its runtime and shows the next one`() = runBlocking {
        val profiles = profiles()
        profiles.load()
        val work = profiles.enroll(credentials("work.example"))
        val home = profiles.enroll(credentials("home.example", "phone-home"))

        profiles.forget(home.id, keepData = false)

        assertEquals(listOf(work.id), profiles.state.value.index.profiles.map { it.id })
        assertEquals(work.id, profiles.state.value.index.current)
        assertNull(profiles.runtime(home.id))
        assertFalse(store().dir(home.id).exists())
    }

    @Test
    fun `forgetting with the data kept leaves the profile listed without credentials`() = runBlocking {
        val profiles = profiles()
        profiles.load()
        val work = profiles.enroll(credentials("work.example"))

        profiles.forget(work.id, keepData = true)

        assertEquals(listOf(work.id), profiles.state.value.index.profiles.map { it.id })
        assertTrue(work.store.state() is StoredEnrollment.NotEnrolled)
        // Published as a new enrollment: the list no longer names the old server.
        assertEquals(1, profiles.state.value.enrollments[work.id])
        profiles.enroll(credentials("work.example", "phone-again"))
        assertEquals(2, profiles.state.value.enrollments[work.id])
    }

    @Test
    fun `every path that replaces an enrollment tells the assistant's permissions to end, and no other does`() = runBlocking {
        val told = ArrayList<String>()
        val profiles = Profiles(store(), record.profiles, enrollmentReplaced = { told += it }) { profile, s, currentId ->
            ProfileRuntime(profile.id, s.credentials(profile), s.dir(profile.id), currentId, shared)
        }
        profiles.load()
        val work = profiles.enroll(credentials("work.example"))
        assertTrue("a new profile replaces nothing", told.isEmpty())
        profiles.update(work.id, "Renamed", "crimson"); profiles.setCurrent(work.id)
        assertTrue("a rename and a switch end nothing", told.isEmpty())
        // Forget with the data kept (a runtime exists), then enrolling in place.
        profiles.forget(work.id, keepData = true)
        assertEquals(listOf(work.id), told)
        profiles.enroll(credentials("work.example", "phone-again"))
        assertEquals("enrolled in place", listOf(work.id, work.id), told)
        // Forget with no runtime (the profile is listed but not started) keeping the data.
        val store = store(); val sleeping = store.add(credentials("sleep.example"))
        val second = Profiles(store, record.profiles, enrollmentReplaced = { told += "second:$it" }) { profile, s, currentId ->
            ProfileRuntime(profile.id, s.credentials(profile), s.dir(profile.id), currentId, shared)
        }
        second.load(); second.forget(sleeping.id, keepData = true)
        assertTrue(told.any { it.startsWith("second:") })
        // Removing a profile entirely is the app's forgetProfile (it ends them first), not an enrollment replacement.
        val before = told.size; profiles.forget(work.id, keepData = false); assertEquals(before, told.size)
    }

    @Test
    fun `profiles unreadable at first are tried again, the restore waiting meanwhile`() = runBlocking {
        // Tunnels of a profile no longer listed: known once the profiles are read.
        record.set("gone", 1)
        val index = File(filesDir, ProfileFiles.INDEX)
        index.writeText("{not json")
        val profiles = profiles()
        val loading = async { profiles.loadRetrying(listOf(200L, 200L, 200L)) }
        withTimeout(5_000) { profiles.state.first { it.retrying } }
        assertNull("not known yet", profiles.restorePending.first())
        index.delete()
        assertTrue(loading.await())
        assertEquals(0, profiles.restorePending.first())
    }

    @Test
    fun `profiles that stay unreadable bring nothing back`() = runBlocking {
        record.set("gone", 1)
        File(filesDir, ProfileFiles.INDEX).writeText("{not json")
        val profiles = profiles()
        assertFalse(profiles.loadRetrying(listOf(10L)))
        assertEquals(0, profiles.restorePending.first())
    }
}
