package ru.adsrv.frpjump.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class SettingsStoreTest {
    @Test
    fun `defaults are background off, p2p, ports 40000-40999`() {
        val settings = SettingsStore(MemoryStorage()).settings.value
        assertEquals(BackgroundPoll.OFF, settings.backgroundPoll)
        assertEquals(false, settings.relayOnly)
        assertEquals(40000..40999, settings.localPorts)
    }

    @Test
    fun `changes persist`() {
        val storage = MemoryStorage()
        SettingsStore(storage).update { it.copy(backgroundPoll = BackgroundPoll.SECONDS_30, relayOnly = true, localPortFirst = 50000, localPortLast = 50009) }
        val reread = SettingsStore(storage).settings.value
        assertEquals(BackgroundPoll.SECONDS_30, reread.backgroundPoll)
        assertEquals(true, reread.relayOnly)
        assertEquals(50000..50009, reread.localPorts)
    }

    @Test
    fun `unreadable or invalid files fall back to the defaults`() {
        assertEquals(AppSettings(), SettingsStore(MemoryStorage("{not json")).settings.value)
        val bad = SettingsStore(MemoryStorage("""{"relay_only":true,"local_port_first":80,"local_port_last":90}""")).settings.value
        assertEquals(true, bad.relayOnly)
        assertEquals(40000..40999, bad.localPorts)
    }

    @Test
    fun `port ranges are 1024-65535 with at least 10 ports`() {
        assertNull(portRangeProblem(1024, 1033))
        assertNull(portRangeProblem(65526, 65535))
        assertNotNull(portRangeProblem(1023, 1100))
        assertNotNull(portRangeProblem(60000, 65536))
        assertNotNull(portRangeProblem(40000, 40008))
        assertNotNull(portRangeProblem(40010, 40000))
        assertNotNull(portRangeProblem(null, 40000))
    }
}
