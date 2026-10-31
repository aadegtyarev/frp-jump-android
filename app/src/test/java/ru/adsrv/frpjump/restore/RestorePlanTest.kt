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
        assertNull(pendingRestore(null, listed, emptyMap()))
        assertNull("profiles not read yet, or tried again", pendingRestore(mapOf("work" to 2), null, emptyMap()))
        assertEquals("an empty record needs no profiles", 0, pendingRestore(emptyMap(), null, emptyMap()))
    }

    @Test
    fun `recorded profiles without tunnels yet are pending, in every profile`() {
        val record = mapOf("work" to 2, "home" to 1)
        assertEquals(3, pendingRestore(record, listed, emptyMap()))
        assertEquals("work is back", 1, pendingRestore(record, listed, mapOf("work" to 2)))
        assertEquals(0, pendingRestore(record, listed, mapOf("work" to 2, "home" to 1)))
    }

    @Test
    fun `profiles no longer listed, or not readable at all, bring nothing back`() {
        assertEquals(0, pendingRestore(mapOf("gone" to 2), listed, emptyMap()))
        assertEquals(0, pendingRestore(mapOf("work" to 2), emptySet(), emptyMap()))
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
