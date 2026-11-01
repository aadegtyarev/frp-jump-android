package ru.adsrv.frpjump.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PresenceTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    @Test
    fun naiveTimestampIsUtc() {
        assertEquals(Instant.parse("2026-10-30T11:59:50.123456Z"), parseServerTime("2026-10-30T11:59:50.123456"))
        assertEquals(Instant.parse("2026-10-30T11:59:50Z"), parseServerTime("2026-10-30T11:59:50"))
    }

    @Test
    fun offsetIsHonoured() {
        assertEquals(Instant.parse("2026-10-30T11:59:50Z"), parseServerTime("2026-10-30T11:59:50+00:00"))
        assertEquals(Instant.parse("2026-10-30T11:59:50Z"), parseServerTime("2026-10-30T14:59:50+03:00"))
    }

    @Test
    fun garbageTimestamp() {
        assertNull(parseServerTime("yesterday"))
        assertEquals(Presence(online = false, lastSeen = null), presenceOf("yesterday", now))
    }

    @Test
    fun neverSeen() {
        assertEquals(Presence(online = false, lastSeen = null), presenceOf(null, now))
    }

    @Test
    fun onlineWithinThirtySeconds() {
        assertTrue(presenceOf("2026-10-30T11:59:58.500000", now).online)
        assertTrue(presenceOf("2026-10-30T11:59:30", now).online)
        assertFalse(presenceOf("2026-10-30T11:59:29.999999", now).online)
    }

    @Test
    fun serverClockSlightlyAhead() {
        val p = presenceOf("2026-10-30T12:00:03", now)
        assertTrue(p.online)
        assertEquals(Age(AgeUnit.NOW, 0), p.lastSeen)
    }

    @Test
    fun relativeLastSeen() {
        assertEquals(Age(AgeUnit.NOW, 0), presenceOf("2026-10-30T11:59:58", now).lastSeen)
        assertEquals(Age(AgeUnit.SECONDS, 12), presenceOf("2026-10-30T11:59:48", now).lastSeen)
        assertEquals(Age(AgeUnit.MINUTES, 5), presenceOf("2026-10-30T11:55:00", now).lastSeen)
        assertEquals(Age(AgeUnit.HOURS, 2), presenceOf("2026-10-30T09:30:00", now).lastSeen)
        assertEquals(Age(AgeUnit.DAYS, 3), presenceOf("2026-10-27T12:00:00", now).lastSeen)
        assertEquals(Age(AgeUnit.NOW, 0), relativeAge(Duration.ofSeconds(-40)))
    }
}
