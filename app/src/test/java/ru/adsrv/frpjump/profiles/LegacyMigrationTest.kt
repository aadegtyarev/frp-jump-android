package ru.adsrv.frpjump.profiles

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.web.WebSiteMemory
import java.io.File
import java.io.IOException

class LegacyMigrationTest {
    private val filesDir = scratchDir()
    private val files = FileStorages()
    private val keystore = FakeKeystore()
    private var ids = 0

    private fun store() = ProfileStore(filesDir, keystore::box, files::storage, newId = { "id${++ids}" })

    private fun file(name: String) = File(filesDir, name)

    /** filesDir as v1 (0.1.1) left it, the enrollment sealed with v1's key. */
    private fun v1Layout(enrolled: Boolean = true) {
        if (enrolled) {
            CredentialStore(files.storage(file("enrollment.json")), keystore.box(LEGACY_KEY_ALIAS)).save(credentials("ikar.example"))
        }
        file("device_list.json").writeText("""{"control_url":"https://ikar.example","device_name":"phone","fetched_at_ms":1,"devices":[]}""")
        file("device_ports.json").writeText("""{"wb":[{"port":8080,"label":"Node-RED","kind":"web"}]}""")
        file("ssh_host_keys.json").writeText("""{"wb":{"22":{"type":"ssh-ed25519","fingerprint":"SHA256:x"}}}""")
        file("ssh_logins.json").writeText("""{"wb":{"22":{}}}""")
        file("local_ports.json").writeText("""{"wb:80":40002,"wb:22":40001}""")
        file("web_site.json").writeText("""["wb","40002"]""")
        file("ssh_passwords.json").writeText("""{"[\"wb\",\"22\",\"root\"]":"sealed-pw"}""")
        file("settings.json").writeText("{}")
        file("ssh_keys.json").writeText("{}")
    }

    private fun crashOnce(block: () -> Unit) {
        try {
            block()
            fail("the crash was not simulated")
        } catch (_: IOException) {
        }
    }

    /** Everything a finished upgrade must show, whatever happened on the way. */
    private fun assertUpgraded(store: ProfileStore) {
        val profile = store.load().profiles.single()
        assertEquals("id1", profile.id)
        assertEquals(LEGACY_KEY_ALIAS, profile.keyAlias)
        assertEquals(credentials("ikar.example"), store.credentials(profile).load())
        for (name in ProfileFiles.PER_PROFILE) assertTrue("$name moved", File(store.dir("id1"), name).exists())
        assertEquals(mapOf("id1/wb:80" to 40002, "id1/wb:22" to 40001), LocalPortStore(files.storage(file("local_ports.json"))).load())
        assertFalse(WebSiteMemory(files.storage(file("web_site.json"))).isOther("id1", "wb", 40002))
        assertEquals("""{"[\"id1\",\"wb\",\"22\",\"root\"]":"sealed-pw"}""", file("ssh_passwords.json").readText())
        assertFalse(file(ProfileFiles.MIGRATION).exists())
        assertEquals(1, ids)
    }

    @After
    fun cleanUp() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `a fresh install has no profiles and writes nothing`() {
        val index = store().load()
        assertEquals(ProfileIndex(), index)
        assertFalse(file(ProfileFiles.INDEX).exists())
        assertFalse(file(ProfileFiles.MIGRATION).exists())
    }

    @Test
    fun `v1 data becomes the first profile, with v1's key and everything kept`() {
        v1Layout()
        val store = store()
        val index = store.load()

        val profile = index.profiles.single()
        assertEquals(Profile("id1", "ikar.example", PROFILE_COLORS[0], LEGACY_KEY_ALIAS), profile)
        assertEquals("id1", index.current)
        // Not re-sealed: the same Keystore key opens it where it now lives.
        assertEquals(credentials("ikar.example"), store.credentials(profile).load())
        val dir = store.dir("id1")
        for (name in ProfileFiles.PER_PROFILE) {
            assertTrue("$name moved", File(dir, name).exists())
            assertFalse("$name gone from filesDir", file(name).exists())
        }
        assertTrue(File(dir, "device_ports.json").readText().contains("Node-RED"))
        assertEquals(mapOf("id1/wb:80" to 40002, "id1/wb:22" to 40001), LocalPortStore(files.storage(file("local_ports.json"))).load())
        assertFalse(WebSiteMemory(files.storage(file("web_site.json"))).isOther("id1", "wb", 40002))
        // The sealed value stays as it is: the shared SSH key opens it.
        assertEquals("""{"[\"id1\",\"wb\",\"22\",\"root\"]":"sealed-pw"}""", file("ssh_passwords.json").readText())
        // Shared by all profiles: left where they are.
        assertTrue(file("settings.json").exists())
        assertTrue(file("ssh_keys.json").exists())
        assertFalse(file(ProfileFiles.MIGRATION).exists())
    }

    @Test
    fun `a second start changes nothing`() {
        v1Layout()
        val first = store().load()
        val ports = file("local_ports.json").readText()
        assertEquals(first, store().load())
        assertEquals(ports, file("local_ports.json").readText())
        assertEquals(1, ids)
    }

    @Test
    fun `a crash before the index is written is finished on the next start, with the same id`() {
        v1Layout()
        files.crashOn = ProfileFiles.INDEX
        try {
            store().load()
            fail("the crash was not simulated")
        } catch (_: IOException) {
        }
        // Halfway: the files moved, the profile not listed yet; the marker remembers it.
        assertFalse(file("enrollment.json").exists())
        assertTrue(file(ProfileFiles.MIGRATION).exists())

        val store = store()
        val profile = store.load().profiles.single()
        assertEquals("id1", profile.id)
        assertEquals(credentials("ikar.example"), store.credentials(profile).load())
        assertEquals(mapOf("id1/wb:80" to 40002, "id1/wb:22" to 40001), LocalPortStore(files.storage(file("local_ports.json"))).load())
        assertFalse(file(ProfileFiles.MIGRATION).exists())
    }

    @Test
    fun `a crash while rewriting the shared maps is finished on the next start`() {
        v1Layout()
        files.crashOn = "local_ports.json"
        try {
            store().load()
            fail("the crash was not simulated")
        } catch (_: IOException) {
        }
        val store = store()
        val profile = store.load().profiles.single()
        assertEquals(credentials("ikar.example"), store.credentials(profile).load())
        assertEquals(mapOf("id1/wb:80" to 40002, "id1/wb:22" to 40001), LocalPortStore(files.storage(file("local_ports.json"))).load())
    }

    @Test
    fun `a crash before anything moved starts over cleanly`() {
        v1Layout()
        files.crashOn = ProfileFiles.MIGRATION
        try {
            store().load()
            fail("the crash was not simulated")
        } catch (_: IOException) {
        }
        assertTrue(file("enrollment.json").exists())
        val store = store()
        val profile = store.load().profiles.single()
        assertEquals(credentials("ikar.example"), store.credentials(profile).load())
    }

    @Test
    fun `a crash while rewriting the web site memory is finished on the next start`() {
        v1Layout()
        files.crashOn = "web_site.json"
        crashOnce { store().load() }
        assertUpgraded(store())
    }

    @Test
    fun `a crash after listing the profile, before the marker goes, is finished without a second profile`() {
        v1Layout()
        files.crashOnDelete = ProfileFiles.MIGRATION
        crashOnce { store().load() }
        assertTrue(file(ProfileFiles.MIGRATION).exists())
        assertUpgraded(store())
    }

    @Test
    fun `a crash between the moves of the per-profile files is finished on the next start`() {
        v1Layout()
        // As the first run left it: the plan written, the enrollment and the device list moved, the rest not yet.
        file(ProfileFiles.MIGRATION).writeText("""{"profile_id":"id1","name":"ikar.example"}""")
        ids = 1
        val dir = File(filesDir, "profiles/id1").apply { mkdirs() }
        for (name in listOf("enrollment.json", "device_list.json")) file(name).renameTo(File(dir, name))
        assertUpgraded(store())
    }

    @Test
    fun `what AtomicFile left next to a v1 file moves with it`() {
        v1Layout()
        file("device_ports.json.bak").writeText("old")
        file("ssh_logins.json.new").writeText("half written")
        val store = store()
        store.load()
        assertEquals("old", File(store.dir("id1"), "device_ports.json.bak").readText())
        assertEquals("half written", File(store.dir("id1"), "ssh_logins.json.new").readText())
        assertFalse(file("device_ports.json.bak").exists())
        assertFalse(file("ssh_logins.json.new").exists())
    }

    // The app makes the shared stores in onCreate, before the upgrade runs off the main thread:
    // each must see the upgraded file and must not write a stale copy back over it.

    @Test
    fun `remembered passwords made before the upgrade keep the upgraded ones`() {
        v1Layout()
        val passwords = SshPasswords(files.storage(file("ssh_passwords.json")), keystore.box("frpjump-ssh"))
        store().load()
        assertTrue(passwords.has("id1", "wb", 22, "root"))
        passwords.remember("id1", "wb", 2222, "admin", "pw")
        val restarted = SshPasswords(files.storage(file("ssh_passwords.json")), keystore.box("frpjump-ssh"))
        assertTrue(restarted.has("id1", "wb", 22, "root"))
        assertTrue(restarted.has("id1", "wb", 2222, "admin"))
    }

    @Test
    fun `the local port map made before the upgrade keeps the upgraded ports`() {
        v1Layout()
        val ports = LocalPortStore(files.storage(file("local_ports.json")))
        store().load()
        ports.update { it["id1/wb:8080"] = 40003 }
        assertEquals(
            mapOf("id1/wb:80" to 40002, "id1/wb:22" to 40001, "id1/wb:8080" to 40003),
            LocalPortStore(files.storage(file("local_ports.json"))).load(),
        )
    }

    @Test
    fun `the web site memory made before the upgrade sees the upgraded site`() {
        v1Layout()
        val sites = WebSiteMemory(files.storage(file("web_site.json")))
        store().load()
        assertFalse(sites.isOther("id1", "wb", 40002))
        sites.remember("id1", "wb", 40003)
        assertFalse(WebSiteMemory(files.storage(file("web_site.json"))).isOther("id1", "wb", 40003))
    }

    @Test
    fun `v1 data without an enrollment becomes a profile to enroll into, keeping its data`() {
        v1Layout(enrolled = false)
        val store = store()
        val profile = store.load().profiles.single()
        assertEquals("", profile.name)
        assertNull(store.credentials(profile).load())

        val filled = store.fill(profile, credentials("ikar.example"))
        assertEquals("ikar.example", filled.name)
        assertEquals(filled, store.index().profiles.single())
        assertTrue(File(store.dir(profile.id), "ssh_host_keys.json").exists())
    }
}
