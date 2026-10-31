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
        assertEquals(true, settings.direct)
        assertEquals(40000..40999, settings.localPorts)
    }

    @Test
    fun `changes persist`() {
        val storage = MemoryStorage()
        SettingsStore(storage).update { it.copy(backgroundPoll = BackgroundPoll.SECONDS_30, direct = false, localPortFirst = 50000, localPortLast = 50009) }
        val reread = SettingsStore(storage).settings.value
        assertEquals(BackgroundPoll.SECONDS_30, reread.backgroundPoll)
        assertEquals(false, reread.direct)
        assertEquals(50000..50009, reread.localPorts)
    }

    @Test
    fun `unreadable or invalid files fall back to the defaults`() {
        assertEquals(AppSettings(), SettingsStore(MemoryStorage("{not json")).settings.value)
        val bad = SettingsStore(MemoryStorage("""{"direct":false,"local_port_first":80,"local_port_last":90}""")).settings.value
        assertEquals(false, bad.direct)
        assertEquals(40000..40999, bad.localPorts)
    }

    @Test
    fun `the old relay-only switch becomes direct, the other way round`() {
        val relayOnly = MemoryStorage("""{"background_poll":"1m","relay_only":true,"local_port_first":40000,"local_port_last":40999}""")
        val migrated = SettingsStore(relayOnly).settings.value
        assertEquals(false, migrated.direct)
        assertEquals(BackgroundPoll.MINUTE_1, migrated.backgroundPoll)
        assertEquals(true, SettingsStore(MemoryStorage("""{"relay_only":false}""")).settings.value.direct)
        assertEquals(true, SettingsStore(MemoryStorage("""{"background_poll":"30s"}""")).settings.value.direct)
    }

    @Test
    fun `once saved, the new key wins and the old one is gone`() {
        val storage = MemoryStorage("""{"relay_only":true}""")
        SettingsStore(storage).update { it.copy(direct = true) }
        assertEquals(false, storage.text!!.contains("relay_only"))
        assertEquals(true, SettingsStore(storage).settings.value.direct)
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

    @Test
    fun `theme follows the system, the lock and hiding in Recents are off by default`() {
        val settings = SettingsStore(MemoryStorage()).settings.value
        assertEquals(ThemeMode.SYSTEM, settings.theme)
        assertEquals(false, settings.appLock)
        assertEquals(false, settings.hideInRecents)
    }

    @Test
    fun `theme and lock persist`() {
        val storage = MemoryStorage()
        SettingsStore(storage).update { it.copy(theme = ThemeMode.DARK, appLock = true, hideInRecents = true) }
        val reread = SettingsStore(storage).settings.value
        assertEquals(ThemeMode.DARK, reread.theme)
        assertEquals(true, reread.appLock)
        assertEquals(true, reread.hideInRecents)
        SettingsStore(storage).update { it.copy(theme = ThemeMode.LIGHT) }
        assertEquals(ThemeMode.LIGHT, SettingsStore(storage).settings.value.theme)
    }

    @Test
    fun `settings of an earlier version get the system theme and keep the rest`() {
        val old = MemoryStorage("""{"background_poll":"5m","direct":false,"language":"ru","local_port_first":41000,"local_port_last":41099}""")
        val migrated = SettingsStore(old).settings.value
        assertEquals(ThemeMode.SYSTEM, migrated.theme)
        assertEquals(false, migrated.appLock)
        assertEquals(false, migrated.hideInRecents)
        assertEquals(BackgroundPoll.MINUTES_5, migrated.backgroundPoll)
        assertEquals(false, migrated.direct)
        assertEquals(41000..41099, migrated.localPorts)
    }

    @Test
    fun `a theme this version does not know is the system one, the rest kept`() {
        val newer = SettingsStore(MemoryStorage("""{"theme":"amoled","direct":false,"app_lock":true}""")).settings.value
        assertEquals(ThemeMode.SYSTEM, newer.theme)
        assertEquals(false, newer.direct)
        assertEquals(true, newer.appLock)
    }
}
