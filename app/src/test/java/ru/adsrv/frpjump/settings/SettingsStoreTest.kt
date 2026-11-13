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
    fun `terminal direct input is on for fresh installations and files without the setting`() {
        assertEquals(true, SettingsStore(MemoryStorage()).settings.value.terminalDirectInput)
        val old = SettingsStore(MemoryStorage("""{"direct":false,"terminal_fullscreen":true}""")).settings.value
        assertEquals(true, old.terminalDirectInput)
        assertEquals(true, old.terminalFullscreen)
        assertEquals(false, old.direct)
    }

    @Test
    fun `explicitly saved terminal direct input choices override the new default`() {
        for (choice in listOf(false, true)) {
            val storage = MemoryStorage("""{"terminal_direct_input":$choice,"direct":false}""")
            val store = SettingsStore(storage)
            assertEquals(choice, store.settings.value.terminalDirectInput)
            store.update { it.copy(terminalFullscreen = true) }
            assertEquals(choice, SettingsStore(storage).settings.value.terminalDirectInput)
        }
    }

    @Test
    fun `terminal direct input persists independently and can be turned off`() {
        val storage = MemoryStorage()
        SettingsStore(storage).update { it.copy(terminalDirectInput = true, direct = false) }
        assertEquals(true, SettingsStore(storage).settings.value.terminalDirectInput)
        SettingsStore(storage).update { it.copy(terminalDirectInput = false) }
        val reread = SettingsStore(storage).settings.value
        assertEquals(false, reread.terminalDirectInput)
        assertEquals(false, reread.direct)
    }

    @Test
    fun `terminal history defaults to 1000 for new and old settings`() {
        assertEquals(1000, SettingsStore(MemoryStorage()).settings.value.terminalHistoryRows)
        val old = SettingsStore(MemoryStorage("""{"direct":false,"terminal_fullscreen":true}""")).settings.value
        assertEquals(1000, old.terminalHistoryRows)
        assertEquals(false, old.direct)
        assertEquals(true, old.terminalFullscreen)
    }

    @Test
    fun `all supported terminal history choices persist`() {
        assertEquals(listOf(500, 1000, 3000, 10000), TERMINAL_HISTORY_CHOICES)
        val storage = MemoryStorage()
        for (rows in TERMINAL_HISTORY_CHOICES) {
            SettingsStore(storage).update { it.copy(terminalHistoryRows = rows, direct = false) }
            val reread = SettingsStore(storage).settings.value
            assertEquals(rows, reread.terminalHistoryRows)
            assertEquals(false, reread.direct)
        }
    }

    @Test
    fun `invalid history values or types do not discard unrelated settings`() {
        for (value in listOf("-1", "0", "99", "50001", "2147483648", "null", "true", "\"3000\"", "[]", "{}")) {
            val storage = MemoryStorage("""{"terminal_history_rows":$value,"direct":false,"theme":"dark","local_port_first":41000,"local_port_last":41099}""")
            val store = SettingsStore(storage)
            assertEquals(1000, store.settings.value.terminalHistoryRows)
            assertEquals(false, store.settings.value.direct)
            assertEquals(ThemeMode.DARK, store.settings.value.theme)
            assertEquals(41000..41099, store.settings.value.localPorts)
            store.update { it.copy(terminalFullscreen = true) }
            assertEquals(1000, SettingsStore(storage).settings.value.terminalHistoryRows)
            assertEquals(ThemeMode.DARK, SettingsStore(storage).settings.value.theme)
        }
    }

    @Test
    fun `invalid history writes are rejected without changing memory or storage`() {
        val storage = MemoryStorage()
        val store = SettingsStore(storage)
        val before = store.settings.value
        assertEquals(true, runCatching { store.update { it.copy(terminalHistoryRows = 1234) } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, store.settings.value)
        assertNull(storage.text)
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

    @Test
    fun `files hide dot names and have no download folder until chosen, then remember both`() {
        val storage = MemoryStorage()
        val fresh = SettingsStore(storage).settings.value
        assertEquals(false, fresh.filesShowHidden)
        assertNull(fresh.filesDownloadFolder)
        val folder = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        SettingsStore(storage).update { it.copy(filesShowHidden = true, filesDownloadFolder = folder) }
        val reread = SettingsStore(storage).settings.value
        assertEquals(true, reread.filesShowHidden)
        assertEquals(folder, reread.filesDownloadFolder)
    }

    @Test
    fun `lock after is 30 s when missing, invalid or unknown, and other fields are kept`() {
        assertEquals(AppLockAfter.SECONDS_30, SettingsStore(MemoryStorage()).settings.value.appLockAfter)
        val bad = listOf("\"2h\"", "5", "null", "true", "{}", "[]", "\"\"", "\"30S\"")
        for (raw in listOf("") + bad.map { ",\"app_lock_after\":$it" }) {
            val s = SettingsStore(MemoryStorage("""{"direct":false,"app_lock":true,"theme":"dark"$raw}""")).settings.value
            assertEquals(raw, AppLockAfter.SECONDS_30, s.appLockAfter)
            assertEquals(raw, false, s.direct)
            assertEquals(raw, true, s.appLock)
            assertEquals(raw, ThemeMode.DARK, s.theme)
        }
    }

    @Test
    fun `every lock after interval is read and survives a rewrite`() {
        for ((key, after) in mapOf("0s" to AppLockAfter.IMMEDIATELY, "30s" to AppLockAfter.SECONDS_30, "1m" to AppLockAfter.MINUTE_1,
            "5m" to AppLockAfter.MINUTES_5, "15m" to AppLockAfter.MINUTES_15)) {
            val storage = MemoryStorage("""{"app_lock":true,"app_lock_after":"$key"}""")
            val store = SettingsStore(storage)
            assertEquals(key, after, store.settings.value.appLockAfter)
            store.update { it.copy(direct = false) }
            assertEquals(key, after, SettingsStore(storage).settings.value.appLockAfter)
        }
        assertEquals(listOf(0L, 30_000L, 60_000L, 300_000L, 900_000L), AppLockAfter.entries.map { it.ms })
    }
}
