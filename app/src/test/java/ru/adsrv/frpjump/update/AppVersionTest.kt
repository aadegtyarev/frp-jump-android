package ru.adsrv.frpjump.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `pre-releases come before their release, in order`() {
        val ordered = listOf("0.1.1", "0.2.0-alpha", "0.2.0-beta1", "0.2.0-beta2", "0.2.0-beta10", "0.2.0-rc1", "0.2.0", "0.2.1", "0.10.0", "1.0.0")
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue("$a < $b", isNewer(b, a))
            assertFalse("$b > $a", isNewer(a, b))
        }
    }

    @Test
    fun `semver pre-release rules`() {
        // Numbers before words, more parts after fewer.
        assertTrue(isNewer("1.0.0-alpha.beta", "1.0.0-alpha.1"))
        assertTrue(isNewer("1.0.0-alpha.1", "1.0.0-alpha"))
        assertTrue(isNewer("1.0.0-beta.11", "1.0.0-beta.2"))
    }

    @Test
    fun `the same version is not newer, however written`() {
        assertFalse(isNewer("v0.2.0", "0.2.0"))
        assertFalse(isNewer("0.2", "0.2.0"))
        assertFalse(isNewer("0.2.0+build5", "0.2.0"))
        assertFalse(isNewer("0.2.0-beta1", "0.2.0-beta1"))
    }

    @Test
    fun `tags parse with or without v`() {
        assertEquals("0.2.1", AppVersion.parse("v0.2.1").toString())
        assertEquals("0.2.0-beta2", AppVersion.parse("V0.2.0-beta2").toString())
        assertEquals("1.0.0", AppVersion.parse("1").toString())
    }

    @Test
    fun `what is not a version is never newer`() {
        listOf("", "latest", "0.2.x", "1.2.3.4", "0.2.0-", "0.2.0-be ta", "release-0.3").forEach {
            assertNull(it, AppVersion.parse(it))
            assertFalse(it, isNewer(it, "0.1.0"))
        }
        assertFalse(isNewer("0.3.0", "dev"))
    }
}
