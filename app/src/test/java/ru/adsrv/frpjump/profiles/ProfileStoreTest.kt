package ru.adsrv.frpjump.profiles

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.store.StoredEnrollment
import java.io.File

class ProfileStoreTest {
    private val filesDir = scratchDir()
    private val files = FileStorages()
    private val keystore = FakeKeystore()
    private var ids = 0

    private fun store() = ProfileStore(filesDir, keystore::box, files::storage, newId = { "id${++ids}" })

    @After
    fun cleanUp() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `each profile seals with a key of its own`() {
        val store = store()
        val work = store.add(credentials("ikar.example", "phone"))
        val home = store.add(credentials("ikar.example", "phone-home"))
        assertNotEquals(work.keyAlias, home.keyAlias)
        assertEquals(setOf(work.keyAlias, home.keyAlias), keystore.keys.keys)
        // One profile's file cannot be opened with the other's key.
        val crossed = CredentialStore(files.storage(File(store.dir(work.id), ProfileFiles.ENROLLMENT)), keystore.box(home.keyAlias))
        assertFalse(crossed.state() is StoredEnrollment.Enrolled)
    }

    @Test
    fun `profiles survive a restart, the last added shown`() {
        val first = store()
        val work = first.add(credentials("work.example"))
        val home = first.add(credentials("home.example", "phone-home"))
        val again = store()
        val index = again.load()
        assertEquals(listOf(work, home), index.profiles)
        assertEquals(home.id, index.current)
        assertEquals(credentials("work.example"), again.credentials(work).load())
        assertEquals("work.example", work.name)
        assertEquals(PROFILE_COLORS.take(2), index.profiles.map { it.color })
    }

    @Test
    fun `forgetting a profile wipes only its files and key and shows the next one`() {
        val store = store()
        val work = store.add(credentials("work.example"))
        val home = store.add(credentials("home.example", "phone-home"))
        store.setCurrent(work.id)

        val after = store.remove(work.id)

        assertEquals(listOf(home), after.profiles)
        assertEquals(home.id, after.current)
        assertFalse(store.dir(work.id).exists())
        assertEquals(setOf(home.keyAlias), keystore.keys.keys)
        assertEquals(credentials("home.example", "phone-home"), store().apply { load() }.credentials(home).load())
        assertEquals(after, store().load())
    }

    @Test
    fun `forgetting the last profile leaves none`() {
        val store = store()
        val only = store.add(credentials("work.example"))
        val after = store.remove(only.id)
        assertTrue(after.profiles.isEmpty())
        assertNull(after.current)
        assertTrue(keystore.keys.isEmpty())
    }

    @Test
    fun `the last profile's shared data goes before the index drops it`() {
        val store = store()
        val only = store.add(credentials("work.example"))
        files.crashOn = ProfileFiles.INDEX
        var wiped = false
        runCatching { store.remove(only.id) { wiped = true } }
        // Interrupted after the shared wipe: still listed, so it can be forgotten again.
        assertTrue(wiped)
        assertEquals(listOf(only), store().load().profiles)

        var again = false
        store.remove(only.id) { again = true }
        assertTrue(again)
        assertTrue(store().load().profiles.isEmpty())
    }

    @Test
    fun `forgetting one of several profiles leaves the shared data`() {
        val store = store()
        val work = store.add(credentials("work.example"))
        store.add(credentials("home.example", "phone-home"))
        var wiped = false
        store.remove(work.id) { wiped = true }
        assertFalse(wiped)
    }

    @Test
    fun `directories and keys of unlisted profiles are swept on load`() {
        val store = store()
        val kept = store.add(credentials("work.example"))
        // A remove interrupted before its directory went, or a late write recreating it.
        File(filesDir, "profiles/gone").mkdirs()
        File(filesDir, "profiles/gone/ssh_logins.json").writeText("{}")
        keystore.box(profileKeyAlias("gone")).seal("x")

        store().load()

        assertFalse(File(filesDir, "profiles/gone").exists())
        assertEquals(setOf(kept.keyAlias), keystore.keys.keys)
        assertTrue(store.dir(kept.id).exists())
    }

    @Test
    fun `a profile that cannot be listed leaves no credentials behind`() {
        val store = store()
        files.crashOn = ProfileFiles.INDEX
        runCatching { store.add(credentials("work.example")) }
        assertFalse(store.dir("id1").exists())
        assertTrue(keystore.keys.isEmpty())
        assertTrue(store.index().profiles.isEmpty())
    }
}
