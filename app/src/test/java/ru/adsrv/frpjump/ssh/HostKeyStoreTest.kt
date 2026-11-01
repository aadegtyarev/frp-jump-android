package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostKeyStoreTest {
    private val wbKey = PinnedHostKey("ssh-ed25519", "SHA256:c9GWFPbZxoNfOZKf9KkTtp5CeHzHMXEcOcHBZjOXk/8")
    private val otherKey = PinnedHostKey("ssh-ed25519", "SHA256:/xfapJWzsQEgdBsECu0MUFfRoWFvr5Vr0fBrCW4gOqc")

    @Test
    fun firstUseIsUnknownThenTrustedOncePinned() {
        val store = HostKeyStore(MemoryStorage())
        assertEquals(HostKeyCheck.Unknown, store.check("wb", 22, wbKey))
        store.pin("wb", 22, wbKey)
        assertEquals(HostKeyCheck.Trusted, store.check("wb", 22, wbKey))
    }

    @Test
    fun anotherKeyIsAMismatchShowingThePinnedOne() {
        val store = HostKeyStore(MemoryStorage())
        store.pin("wb", 22, wbKey)
        assertEquals(HostKeyCheck.Changed(wbKey), store.check("wb", 22, otherKey))
        // Same fingerprint under another algorithm name is not the same key either.
        assertEquals(HostKeyCheck.Changed(wbKey), store.check("wb", 22, wbKey.copy(type = "ssh-rsa")))
    }

    @Test
    fun pinsArePerDeviceAndPort() {
        val store = HostKeyStore(MemoryStorage())
        store.pin("wb", 22, wbKey)
        assertEquals(HostKeyCheck.Unknown, store.check("wb", 2222, wbKey))
        assertEquals(HostKeyCheck.Unknown, store.check("other", 22, wbKey))
    }

    @Test
    fun forgetTheOldKeyAsksAgain() {
        val store = HostKeyStore(MemoryStorage())
        store.pin("wb", 22, wbKey)
        store.pin("wb", 80, otherKey)
        store.forget("wb", 22)
        assertEquals(HostKeyCheck.Unknown, store.check("wb", 22, otherKey))
        assertEquals(otherKey, store.pinned("wb", 80))
    }

    @Test
    fun pinsSurviveARestart() {
        val disk = MemoryStorage()
        HostKeyStore(disk).pin("wb", 22, wbKey)
        assertEquals(HostKeyCheck.Trusted, HostKeyStore(disk).check("wb", 22, wbKey))
        HostKeyStore(disk).wipe()
        assertNull(HostKeyStore(disk).pinned("wb", 22))
    }

    @Test
    fun aCorruptFileIsTreatedAsEmpty() {
        assertEquals(HostKeyCheck.Unknown, HostKeyStore(MemoryStorage("{not json")).check("wb", 22, wbKey))
    }
}
