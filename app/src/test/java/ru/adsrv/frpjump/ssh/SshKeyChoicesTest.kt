package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore

/** The keys "Use key login" may choose between, and the lookup of one by its line. */
class SshKeyChoicesTest {
    private val box = FakeKeystore().box("synthetic-ssh")
    private val shared = MemoryStorage()
    private val store = SshKeyStore(shared, box)

    @Test
    fun `one key is the phones`() {
        val phone = store.publicLine("c")
        assertEquals(listOf(SshKeyChoice(SshKeySlot.PHONE, phone)), store.choices("c"))
    }

    @Test
    fun `two keys are the phones then the imported one, and Remove goes back to one`() {
        val phone = store.publicLine("c")
        val imported = generateEd25519("imported")
        store.import(imported.privateKey, null, imported.publicLine)
        assertEquals(
            listOf(SshKeyChoice(SshKeySlot.PHONE, phone), SshKeyChoice(SshKeySlot.IMPORTED, imported.publicLine)),
            store.choices("c"),
        )
        store.removeImported()
        assertEquals(listOf(SshKeyChoice(SshKeySlot.PHONE, phone)), store.choices("c"))
    }

    @Test
    fun `identity unseals only the key of the line`() {
        val phone = store.publicLine("c")
        val imported = generateEd25519("imported")
        store.import(imported.privateKey, null, imported.publicLine)
        // A box that records what it opens: only the imported key's secret is.
        val opened = mutableListOf<String>()
        val spy = object : ru.adsrv.frpjump.store.SecretBox by box {
            override fun open(sealed: String): String = box.open(sealed).also { opened += it }
        }
        val spied = SshKeyStore(MemoryStorage(shared.text), spy)
        assertNotNull(spied.identity(imported.publicLine))
        assertEquals(listOf(imported.privateKey), opened)
        assertNull(spied.identity("ssh-ed25519 AAAA nobody"))
        assertEquals(phone, store.identity(phone)?.publicLine)
    }
}
