package ru.adsrv.frpjump.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollIntervalTest {
    @Test
    fun `visible polls every 2 s whatever the setting`() {
        assertEquals(2_000L, pollIntervalMs(visible = true, tunnelsUp = false, backgroundMs = null, removed = false))
        assertEquals(2_000L, pollIntervalMs(visible = true, tunnelsUp = true, backgroundMs = 900_000L, removed = false))
    }

    @Test
    fun `background polls at the chosen interval only while tunnels are up`() {
        assertEquals(30_000L, pollIntervalMs(visible = false, tunnelsUp = true, backgroundMs = 30_000L, removed = false))
        assertNull(pollIntervalMs(visible = false, tunnelsUp = false, backgroundMs = 30_000L, removed = false))
    }

    @Test
    fun `background off never polls, tunnels or not`() {
        assertNull(pollIntervalMs(visible = false, tunnelsUp = true, backgroundMs = null, removed = false))
        assertNull(pollIntervalMs(visible = false, tunnelsUp = false, backgroundMs = null, removed = false))
    }

    @Test
    fun `a removed phone never polls`() {
        assertNull(pollIntervalMs(visible = true, tunnelsUp = true, backgroundMs = 30_000L, removed = true))
    }
}
