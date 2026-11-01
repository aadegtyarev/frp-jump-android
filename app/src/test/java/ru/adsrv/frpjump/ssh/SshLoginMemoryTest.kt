package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SshLoginMemoryTest {
    @Test
    fun defaultsToRootWithTheKey() {
        assertEquals(SshLogin("root", SshAuthMethod.KEY), SshLoginMemory(MemoryStorage()).get("wb", 22))
    }

    @Test
    fun remembersPerDeviceAndPortAcrossRestarts() {
        val disk = MemoryStorage()
        SshLoginMemory(disk).remember("wb", 22, SshLogin("admin", SshAuthMethod.PASSWORD))
        val memory = SshLoginMemory(disk)
        assertEquals(SshLogin("admin", SshAuthMethod.PASSWORD), memory.get("wb", 22))
        assertEquals(SshLogin(), memory.get("wb", 2222))
        assertEquals(SshLogin(), memory.get("other", 22))
    }

    @Test
    fun aBlankUserFallsBackToRoot() {
        val memory = SshLoginMemory(MemoryStorage())
        memory.remember("wb", 22, SshLogin("  ", SshAuthMethod.KEY))
        assertEquals("root", memory.get("wb", 22).user)
    }

    @Test
    fun passwordsAreNotWrittenHere() {
        val disk = MemoryStorage()
        SshLoginMemory(disk).remember("wb", 22, SshLogin("admin", SshAuthMethod.PASSWORD))
        assertEquals(false, disk.text.orEmpty().contains("secret"))
        SshLoginMemory(disk).wipe()
        assertEquals(SshLogin(), SshLoginMemory(disk).get("wb", 22))
    }
}
