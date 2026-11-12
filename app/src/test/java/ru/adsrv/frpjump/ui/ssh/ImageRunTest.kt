package ru.adsrv.frpjump.ui.ssh

import androidx.compose.runtime.mutableStateMapOf
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.image.EncodedImage
import ru.adsrv.frpjump.image.ImageFailure
import ru.adsrv.frpjump.image.ImageFailureException
import ru.adsrv.frpjump.image.ImageInsertOutcome
import ru.adsrv.frpjump.image.ImageTarget
import ru.adsrv.frpjump.image.RemoteImages

/** The per-tab bookkeeping of the terminal screen's image runs: one per tab, the bar and the guard on Cancel, a tab that goes. */
class ImageRunTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = mutableStateMapOf<Int, ImageRun>()

    @After fun stop() = scope.cancel()

    private val target = object : ImageTarget {
        override fun open(track: (Closeable) -> Unit): RemoteImages = throw java.io.IOException("not used")
        override fun stillCurrent() = true
        override fun paste(text: String, enter: Boolean) = true
    }

    /** An encode that blocks until [gate] is released, and ends when what it was handed is closed (a stream being read). */
    private fun blocked(gate: CountDownLatch, honoursClose: Boolean = true, started: CountDownLatch? = null): (track: (Closeable) -> Unit) -> EncodedImage = { track ->
        if (honoursClose) track(Closeable { gate.countDown() })
        started?.countDown()
        gate.await(20, TimeUnit.SECONDS)
        throw ImageFailureException(ImageFailure.UNREADABLE)
    }

    private fun waitFor(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < until)
            Thread.sleep(20)
        }
    }

    private fun start(tab: Int, encode: (track: (Closeable) -> Unit) -> EncodedImage, outcomes: MutableList<ImageInsertOutcome> = CopyOnWriteArrayList()) =
        startImageRun(scope, runs, tab, target, encode, insertOn = Dispatchers.Unconfined) { outcomes += it }

    @Test fun oneImagePerTabAndAnotherTabIsIndependent() {
        val gate = CountDownLatch(1)
        val first = start(1, blocked(gate))
        assertNotNull(first)
        assertNull("a second image for the same tab is not started", start(1, blocked(CountDownLatch(1))))
        assertEquals(1, runs.size)
        val other = CountDownLatch(1)
        assertNotNull("another tab has its own", start(2, blocked(other)))
        assertEquals(2, runs.size)
        gate.countDown(); other.countDown()
        waitFor("both runs to end") { runs.isEmpty() }
    }

    @Test fun cancelHidesTheBarAtOnceAndTheTabsGuardStaysUntilTheJobHasEnded() {
        val gate = CountDownLatch(1)
        // A read that ignores the close (a stuck provider): the job cannot end until it returns.
        val started = CountDownLatch(1)
        val run = start(1, blocked(gate, honoursClose = false, started = started))!!
        assertTrue("the read is under way", started.await(5, TimeUnit.SECONDS))
        run.cancel()
        assertTrue("the bar goes at once", run.cancelling)
        Thread.sleep(300)
        assertNotNull("but the tab keeps its one-image guard while the job is still running", runs[1])
        assertNull("so a second image for the tab is refused", start(1, blocked(CountDownLatch(1))))
        gate.countDown()
        waitFor("the job to end and the guard to go") { runs[1] == null }
        assertNotNull("and the tab can have another image now", start(1, blocked(CountDownLatch(0))))
    }

    @Test fun aCancelledRunReportsNoOutcomeAndAFinishedOneDoes() {
        val outcomes = CopyOnWriteArrayList<ImageInsertOutcome>()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val run = start(1, blocked(gate, started = started), outcomes)!!
        assertTrue("the body is under way", started.await(5, TimeUnit.SECONDS))
        run.cancel()
        waitFor("the cancelled run to end") { runs[1] == null }
        assertTrue("no outcome for a cancel", outcomes.isEmpty())
        // One that fails by itself says so.
        start(2, { throw ImageFailureException(ImageFailure.TOO_LARGE) }, outcomes)
        waitFor("the failed run's outcome") { outcomes.isNotEmpty() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.TOO_LARGE), outcomes.single())
        waitFor("its guard to go") { runs[2] == null }
    }

    @Test fun aTabThatIsNoLongerShownTakesItsRunWithIt() {
        val a = CountDownLatch(1)
        val b = CountDownLatch(1)
        val runA = start(1, blocked(a))!!
        val runB = start(2, blocked(b))!!
        cancelRunsOfGoneTabs(runs, shownTabs = setOf(1))
        assertTrue("the closed tab's run is cancelled", runB.cancelling)
        assertFalse("the shown tab's run is not", runA.cancelling)
        waitFor("the cancelled run to end") { runs[2] == null }
        assertNotNull(runs[1])
        a.countDown()
        waitFor("the other run to end") { runs.isEmpty() }
    }

    @Test fun anOutcomeHandlerThatThrowsDoesNotTakeTheScopeDownOrLeakTheGuard() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val called = CountDownLatch(1)
            startImageRun(scope, runs, 1, target, { throw ImageFailureException(ImageFailure.TOO_LARGE) }, insertOn = Dispatchers.Unconfined) {
                called.countDown()
                throw IllegalStateException("the notice failed")
            }
            assertTrue(called.await(5, TimeUnit.SECONDS))
            waitFor("the guard to go") { runs[1] == null }
            Thread.sleep(200)
            assertTrue("nothing escaped: $uncaught", uncaught.isEmpty())
            assertNotNull("the tab can start another image", start(1, blocked(CountDownLatch(0))))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
