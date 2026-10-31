package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time by hand: [advanceBy] fires due timers in order. */
private class FakeClock : ReconnectClock {
    var time = 0L

    private class Timer(val at: Long, val seq: Int, val action: () -> Unit) {
        var cancelled = false
    }

    private val timers = mutableListOf<Timer>()
    private var seq = 0

    override fun now() = time

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val timer = Timer(time + delayMs, seq++, action)
        timers += timer
        return { timer.cancelled = true }
    }

    fun advanceBy(ms: Long) {
        val end = time + ms
        while (true) {
            val next = timers.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            timers -= next
            time = next.at
            next.action()
        }
        time = end
    }
}

class SshReconnectTest {
    private val clock = FakeClock()

    /** When each try started (ms) and its number. */
    private val tries = mutableListOf<Pair<Long, Int>>()
    private var gaveUp = 0

    /** Every try fails at once unless [answer] says otherwise. */
    private var answer: (SshReconnect) -> Unit = { it.failed() }

    private val loop: SshReconnect = SshReconnect(
        clock,
        attempt = { n ->
            tries += clock.now() to n
            answer(loop)
        },
        giveUp = { gaveUp++ },
    )

    @Test
    fun `tries at once, then after 2, 4, 8, 15 s, then every 30 s`() {
        loop.start()
        clock.advanceBy(90_000)
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 29_000L, 59_000L, 89_000L), tries.map { it.first })
        assertEquals((1..7).toList(), tries.map { it.second })
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L), (1..6).map(::reconnectPauseMs))
    }

    @Test
    fun `the network coming back tries at once and the next pause is 2 s again`() {
        loop.start()
        clock.advanceBy(30_000)
        // Tries at 0, 2, 6, 14, 29 s; the next one would be at 59 s.
        assertEquals(5, tries.size)
        clock.advanceBy(5_000)
        loop.networkReturned()
        assertEquals(35_000L to 6, tries.last())
        clock.advanceBy(2_000)
        assertEquals(37_000L to 7, tries.last())
        // Then 4 and 8 s; the timer of 59 s was replaced, not added to.
        clock.advanceBy(23_000)
        assertEquals(listOf(41_000L, 49_000L), tries.drop(7).map { it.first })
    }

    @Test
    fun `the network coming back while a try is under way does not start another`() {
        answer = {}
        loop.start()
        loop.networkReturned()
        loop.now()
        assertEquals(1, tries.size)
        // That try fails: the pause is the shortest one.
        loop.failed()
        clock.advanceBy(2_000)
        assertEquals(2, tries.size)
    }

    @Test
    fun `Now tries at once during a pause`() {
        loop.start()
        clock.advanceBy(10_000)
        // Tries at 0, 2, 6 s; the next at 14 s.
        loop.now()
        assertEquals(10_000L to 4, tries.last())
    }

    @Test
    fun `gives up after 10 minutes without success`() {
        loop.start()
        clock.advanceBy(RECONNECT_GIVE_UP_MS - 1)
        assertEquals(0, gaveUp)
        assertTrue(loop.running)
        clock.advanceBy(1)
        assertEquals(1, gaveUp)
        assertFalse(loop.running)
        val made = tries.size
        clock.advanceBy(10 * 60_000)
        loop.networkReturned()
        loop.now()
        assertEquals(made, tries.size)
        assertEquals(1, gaveUp)
    }

    @Test
    fun `a try that ends past 10 minutes gives up instead of waiting`() {
        answer = {}
        loop.start()
        clock.advanceBy(RECONNECT_GIVE_UP_MS + 5_000)
        loop.failed()
        assertEquals(1, gaveUp)
        assertFalse(loop.running)
    }

    @Test
    fun `stopped (closed, tunnel disconnected, login refused) it tries no more`() {
        loop.start()
        clock.advanceBy(3_000)
        val made = tries.size
        loop.stop()
        clock.advanceBy(RECONNECT_GIVE_UP_MS)
        loop.networkReturned()
        loop.now()
        assertEquals(made, tries.size)
        assertEquals(0, gaveUp)
        assertFalse(loop.running)
    }

    @Test
    fun `a try under way when stopped is not followed by another`() {
        answer = {}
        loop.start()
        loop.stop()
        // The refused login, or the closed connection, reports back after the stop.
        loop.failed()
        clock.advanceBy(60_000)
        assertEquals(1, tries.size)
    }

    @Test
    fun `success ends the loop and the next drop counts from 1 with the short pauses`() {
        loop.start()
        clock.advanceBy(30_000)
        assertEquals(5, tries.last().second)
        answer = { it.succeeded() }
        loop.now()
        assertFalse(loop.running)
        clock.advanceBy(RECONNECT_GIVE_UP_MS)
        assertEquals(0, gaveUp)

        answer = { it.failed() }
        tries.clear()
        loop.start()
        clock.advanceBy(3_000)
        assertEquals(listOf(clock.time - 3_000 to 1, clock.time - 1_000 to 2), tries)
    }

    @Test
    fun `the 10 minutes count from the latest drop`() {
        loop.start()
        clock.advanceBy(9 * 60_000)
        answer = { it.succeeded() }
        loop.now()
        answer = { it.failed() }
        loop.start()
        clock.advanceBy(5 * 60_000)
        assertEquals(0, gaveUp)
        assertTrue(loop.running)
    }

    @Test
    fun `a second drop while trying does not restart the count`() {
        loop.start()
        clock.advanceBy(10_000)
        val made = tries.size
        loop.start()
        assertEquals(made, tries.size)
    }

    @Test
    fun `a tab closed with Undo on screen stops trying at once and Undo resumes`() {
        loop.start()
        clock.advanceBy(3_000)
        // Tries at 0 and 2 s; the next would be at 6 s.
        loop.hold(true)
        clock.advanceBy(60_000)
        loop.networkReturned()
        loop.now()
        assertEquals(2, tries.size)
        assertTrue(loop.running)
        // Undo: at once, then the short pause.
        loop.hold(false)
        assertEquals(63_000L to 3, tries.last())
        clock.advanceBy(2_000)
        assertEquals(65_000L to 4, tries.last())
    }

    @Test
    fun `a try under way when the tab is closed with Undo is not followed by another`() {
        answer = {}
        loop.start()
        loop.hold(true)
        loop.failed()
        clock.advanceBy(60_000)
        assertEquals(1, tries.size)
        answer = { it.failed() }
        loop.hold(false)
        assertEquals(2, tries.size)
    }

    @Test
    fun `a drop while the tab waits for its Undo tries only after Undo`() {
        loop.hold(true)
        loop.start()
        clock.advanceBy(30_000)
        assertTrue(tries.isEmpty())
        loop.hold(false)
        assertEquals(listOf(30_000L to 1), tries)
    }

    @Test
    fun `the close done when Undo goes ends the loop for good`() {
        loop.start()
        loop.hold(true)
        loop.stop()
        loop.hold(false)
        clock.advanceBy(60_000)
        assertEquals(1, tries.size)
        assertFalse(loop.running)
    }

    @Test
    fun `holding a session that is not dropped changes nothing`() {
        loop.hold(true)
        loop.hold(false)
        clock.advanceBy(60_000)
        assertTrue(tries.isEmpty())
        assertFalse(loop.running)
    }

    @Test
    fun `only a read error or a close without exit status is a drop`() {
        assertTrue(shellDropped(readError = true, exitStatus = null, exitSignal = false))
        assertTrue(shellDropped(readError = false, exitStatus = null, exitSignal = false))
        // `exit`, a logout, a killed shell: Ended with Reconnect, as before.
        assertFalse(shellDropped(readError = false, exitStatus = 0, exitSignal = false))
        assertFalse(shellDropped(readError = false, exitStatus = 130, exitSignal = false))
        assertFalse(shellDropped(readError = false, exitStatus = null, exitSignal = true))
    }
}
