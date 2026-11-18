package ru.adsrv.frpjump.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class ConnectionsFilterSettingsTest {
    private fun store(json: String?) = SettingsStore(MemoryStorage(json))

    @Test fun `the filter is on the first time and for files without it`() {
        assertTrue(store(null).settings.value.connectionsOnlyOn)
        assertTrue(store("""{"direct":false}""").settings.value.connectionsOnlyOn)
    }

    @Test fun `the choice is kept across a restart`() {
        val storage = MemoryStorage(null)
        SettingsStore(storage).update { it.copy(connectionsOnlyOn = false) }
        assertFalse(SettingsStore(storage).settings.value.connectionsOnlyOn)
        val again = SettingsStore(storage)
        again.update { it.copy(connectionsOnlyOn = true) }
        assertTrue(SettingsStore(storage).settings.value.connectionsOnlyOn)
    }

    @Test fun `a malformed value reads as on and cannot reset unrelated settings`() {
        for (bad in listOf("null", "\"false\"", "0", "[]", "{}")) {
            val s = store("""{"direct":false,"connections_only_on":$bad}""")
            assertTrue(bad, s.settings.value.connectionsOnlyOn)
            assertEquals(bad, false, s.settings.value.direct)
        }
    }
}
