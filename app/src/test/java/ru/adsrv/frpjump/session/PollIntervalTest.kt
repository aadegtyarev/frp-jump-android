package ru.adsrv.frpjump.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollIntervalTest {
    @Test
    fun `visible polls every 2 s whatever the setting`() {
        assertEquals(2_000L, pollIntervalMs(visible = true, current = true, tunnelsUp = false, backgroundMs = null, removed = false))
        assertEquals(2_000L, pollIntervalMs(visible = true, current = true, tunnelsUp = true, backgroundMs = 900_000L, removed = false))
    }

    @Test
    fun `background polls at the chosen interval only while tunnels are up`() {
        assertEquals(30_000L, pollIntervalMs(visible = false, current = true, tunnelsUp = true, backgroundMs = 30_000L, removed = false))
        assertNull(pollIntervalMs(visible = false, current = true, tunnelsUp = false, backgroundMs = 30_000L, removed = false))
    }

    @Test
    fun `background off never polls, tunnels or not`() {
        assertNull(pollIntervalMs(visible = false, current = true, tunnelsUp = true, backgroundMs = null, removed = false))
        assertNull(pollIntervalMs(visible = false, current = true, tunnelsUp = false, backgroundMs = null, removed = false))
    }

    @Test
    fun `another profile than the shown one polls as if the app were closed`() {
        assertEquals(30_000L, pollIntervalMs(visible = true, current = false, tunnelsUp = true, backgroundMs = 30_000L, removed = false))
        assertNull(pollIntervalMs(visible = true, current = false, tunnelsUp = false, backgroundMs = 30_000L, removed = false))
        assertNull(pollIntervalMs(visible = true, current = false, tunnelsUp = true, backgroundMs = null, removed = false))
    }

    @Test
    fun `a removed profile never polls`() {
        assertNull(pollIntervalMs(visible = true, current = true, tunnelsUp = true, backgroundMs = 30_000L, removed = true))
    }

    @Test
    fun `after a cold start a profile with tunnels to restore polls until they run, whatever the setting`() {
        // Not shown, background checks off: nothing else would start them.
        assertEquals(5_000L, pollIntervalMs(visible = false, current = false, tunnelsUp = false, backgroundMs = null, removed = false, restoring = true))
        assertEquals(5_000L, pollIntervalMs(visible = true, current = false, tunnelsUp = false, backgroundMs = 900_000L, removed = false, restoring = true))
        assertEquals("the shown one at its own pace", 2_000L, pollIntervalMs(visible = true, current = true, tunnelsUp = false, backgroundMs = null, removed = false, restoring = true))
    }

    @Test
    fun `once restored a profile polls as usual`() {
        assertNull(pollIntervalMs(visible = false, current = false, tunnelsUp = true, backgroundMs = null, removed = false, restoring = true))
        assertEquals(60_000L, pollIntervalMs(visible = false, current = false, tunnelsUp = true, backgroundMs = 60_000L, removed = false, restoring = true))
    }

    @Test
    fun `a removed profile is not restored`() {
        assertNull(pollIntervalMs(visible = false, current = false, tunnelsUp = false, backgroundMs = null, removed = true, restoring = true))
    }
}
