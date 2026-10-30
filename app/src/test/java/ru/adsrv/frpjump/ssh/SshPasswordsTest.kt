package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore

class SshPasswordsTest {
    private val storage = MemoryStorage()
    private val box = FakeKeystore().box("frpjump-ssh")

    @Test
    fun `a password belongs to one profile's device, not to a same-named device of another`() {
        val passwords = SshPasswords(storage, box)
        passwords.remember("work", "wb", 22, "root", "secret")
        assertEquals("secret", passwords.get("work", "wb", 22, "root"))
        assertFalse(passwords.has("home", "wb", 22, "root"))
        assertNull(passwords.get("home", "wb", 22, "root"))
    }

    @Test
    fun `forgetting a profile drops only its passwords`() {
        val passwords = SshPasswords(storage, box)
        passwords.remember("work", "wb", 22, "root", "w")
        passwords.remember("home", "wb", 22, "root", "h")
        passwords.forgetProfile("work")
        val restarted = SshPasswords(storage, box)
        assertFalse(restarted.has("work", "wb", 22, "root"))
        assertEquals("h", restarted.get("home", "wb", 22, "root"))
    }

    @Test
    fun `v1 passwords become the migrated profile's, still sealed, once`() {
        // As v1 stored it: keyed by [device, port, user], sealed with the same shared key.
        val v1 = """{"[\"wb\",\"22\",\"root\"]":"${box.seal("secret")}"}"""
        val migrated = SshPasswords.migrateLegacy(v1, "id1")!!
        storage.text = migrated
        assertEquals("secret", SshPasswords(storage, box).get("id1", "wb", 22, "root"))
        assertNull(SshPasswords.migrateLegacy(migrated, "id1"))
        assertTrue(SshPasswords(storage, box).has("id1", "wb", 22, "root"))
    }
}
