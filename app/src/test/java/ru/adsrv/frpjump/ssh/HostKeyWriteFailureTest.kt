package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.store.TextStorage
import java.io.IOException

/** A failed save of the owner's host-key answer is reported, trusts nothing and never hides cancellation. */
class HostKeyWriteFailureTest {
    private val key = PinnedHostKey("ssh-ed25519", "SHA256:c9GWFPbZxoNfOZKf9KkTtp5CeHzHMXEcOcHBZjOXk/8")

    private class BrokenStorage(private val saved: String? = null) : TextStorage {
        override fun read(): String? = saved
        override fun write(text: String) = throw IOException("disk full")
        override fun delete() {}
    }

    @Test
    fun aFailedPinIsReportedAndTrustsNothing() {
        val store = HostKeyStore(BrokenStorage())
        val failure = hostKeyWriteFailure { store.pin("wb", 22, key) }
        assertEquals("disk full", failure?.message)
        assertEquals(HostKeyCheck.Unknown, store.check("wb", 22, key))
        assertNull(store.pinned("wb", 22))
    }

    @Test
    fun aFailedForgetIsReportedAndKeepsThePin() {
        val disk = MemoryStorage()
        HostKeyStore(disk).pin("wb", 22, key)
        val broken = HostKeyStore(BrokenStorage(disk.text))
        assertEquals("disk full", hostKeyWriteFailure { broken.forget("wb", 22) }?.message)
        assertEquals(key, broken.pinned("wb", 22))
    }

    @Test
    fun aSavedWriteIsNoFailure() {
        assertNull(hostKeyWriteFailure { HostKeyStore(MemoryStorage()).pin("wb", 22, key) })
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        try {
            hostKeyWriteFailure { throw CancellationException("stop") }
            fail("swallowed")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test
    fun theOwnerIsToldOfAFailedSave() {
        val save = keyInstallText(KeyInstallResult.HostKeySaveFailed("disk full", forgot = false), "root")!!
        assertEquals(R.string.host_key_save_failed, save.res)
        assertEquals(false, save.ok)
        assertEquals(R.string.host_key_forget_failed, keyInstallText(KeyInstallResult.HostKeySaveFailed("x", forgot = true), "root")!!.res)
    }
}

class WriteFailureKindTest {
    private val path = "/data/user/0/ru.adsrv.frpjump/files/host_keys.json"

    @Test
    fun noSpaceIsNoSpace() {
        assertEquals(WriteFailureKind.NO_SPACE, writeFailureKind(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(WriteFailureKind.NO_SPACE, writeFailureKind(IOException("wrapped", IOException("No space left on device"))))
    }

    @Test
    fun deniedIsNoAccess() {
        assertEquals(WriteFailureKind.NO_ACCESS, writeFailureKind(java.nio.file.AccessDeniedException(path)))
        assertEquals(WriteFailureKind.NO_ACCESS, writeFailureKind(java.io.FileNotFoundException("$path (Permission denied)")))
        assertEquals(WriteFailureKind.NO_ACCESS, writeFailureKind(IOException("open failed: EACCES")))
    }

    @Test
    fun anythingElseIsAWriteError() {
        assertEquals(WriteFailureKind.OTHER, writeFailureKind(IOException("disk full")))
        assertEquals(WriteFailureKind.OTHER, writeFailureKind(IllegalStateException()))
    }

    @Test
    fun theTextHoldsNoPathOrMessage() {
        // Texts is not initialised in a unit test: the only thing the owner can see is the kind's string resource.
        val kinds = listOf(
            writeFailureKind(IOException("$path: disk full")),
            writeFailureKind(java.io.FileNotFoundException("$path (Permission denied)")),
            writeFailureKind(IOException("$path: ENOSPC")),
        )
        assertEquals(listOf(R.string.write_failed_other, R.string.write_failed_no_access, R.string.write_failed_no_space), kinds.map { it.res })
    }
}
