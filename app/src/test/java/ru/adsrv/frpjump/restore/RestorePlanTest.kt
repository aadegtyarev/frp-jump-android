package ru.adsrv.frpjump.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePlanTest {
    private val listed = setOf("work", "home")

    @Test
    fun `unknown until the record and the profiles are read`() {
        assertNull(pendingRestore(null, profilesLoaded = true, profilesFailed = false, listed, emptyMap()))
        assertNull(pendingRestore(mapOf("work" to 2), profilesLoaded = false, profilesFailed = false, emptySet(), emptyMap()))
        assertEquals("an empty record needs no profiles", 0, pendingRestore(emptyMap(), profilesLoaded = false, profilesFailed = false, emptySet(), emptyMap()))
    }

    @Test
    fun `recorded profiles without tunnels yet are pending, in every profile`() {
        val record = mapOf("work" to 2, "home" to 1)
        assertEquals(3, pendingRestore(record, profilesLoaded = true, profilesFailed = false, listed, emptyMap()))
        assertEquals("work is back", 1, pendingRestore(record, profilesLoaded = true, profilesFailed = false, listed, mapOf("work" to 2)))
        assertEquals(0, pendingRestore(record, profilesLoaded = true, profilesFailed = false, listed, mapOf("work" to 2, "home" to 1)))
    }

    @Test
    fun `profiles no longer listed or unreadable bring nothing back`() {
        assertEquals(0, pendingRestore(mapOf("gone" to 2), profilesLoaded = true, profilesFailed = false, listed, emptyMap()))
        assertEquals(0, pendingRestore(mapOf("work" to 2), profilesLoaded = false, profilesFailed = true, emptySet(), emptyMap()))
    }

    @Test
    fun `boot starts the service only with tunnels recorded`() {
        assertEquals(0, tunnelsToRestoreOnBoot(emptyMap()))
        assertEquals(3, tunnelsToRestoreOnBoot(mapOf("work" to 2, "home" to 1)))
    }

    @Test
    fun `the service stays while tunnels are up or still to come back`() {
        assertTrue(serviceNeeded(tunnels = 1, pending = 0))
        assertTrue("restoring", serviceNeeded(tunnels = 0, pending = 2))
        assertTrue("just restarted, not known yet", serviceNeeded(tunnels = 0, pending = null))
        assertFalse("disconnect all, or the last tunnel gone", serviceNeeded(tunnels = 0, pending = 0))
    }
}
