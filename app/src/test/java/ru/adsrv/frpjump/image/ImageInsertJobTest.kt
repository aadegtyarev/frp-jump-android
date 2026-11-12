package ru.adsrv.frpjump.image

import java.io.Closeable
import java.io.IOException
import java.time.LocalDateTime
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The job against a device kept in memory: ordering, cancellation, partial files, identity of the tab. No sockets. */
class ImageInsertJobTest {
    private class Device {
        val files = ConcurrentHashMap<String, ByteArray>()
        val modified = ConcurrentHashMap<String, Long>()
        val notFiles = ConcurrentHashMap.newKeySet<String>()
        val opened = AtomicInteger()
        val channels = CopyOnWriteArrayList<Channel>()
        @Volatile var prepareFails: ImageFailure? = null
        @Volatile var clashes = 0
        @Volatile var removeFails = false
        @Volatile var createHook: (Channel) -> Unit = {}
        @Volatile var listHook: (Channel) -> Unit = {}
        @Volatile var closeDelayMs = 0L
        @Volatile var prepareThrows: Throwable? = null
        @Volatile var failMidWrite = false
        val dir = "/home/u/.cache/frp-jump/images"
        var seq = 0L

        inner class Channel : RemoteImages {
            @Volatile var closed = false
            val closedLatch = CountDownLatch(1)
            private fun open() { if (closed) throw IOException("closed") }
            override fun prepare(): String { open(); prepareThrows?.let { throw it }; prepareFails?.let { throw ImageFailureException(it) }; return dir }
            override fun create(dir: String, name: String, bytes: ByteArray, onCreated: () -> Unit, onProgress: (Long) -> Unit) {
                open()
                if (clashes > 0) { clashes--; throw NameTaken() }
                files["$dir/$name"] = ByteArray(0)
                modified["$dir/$name"] = 100_000L
                onCreated()
                createHook(this)
                if (failMidWrite) throw IOException("lost")
                open()
                files["$dir/$name"] = bytes
                onProgress(bytes.size.toLong())
            }
            override fun modified(dir: String, name: String): Long { open(); return modified["$dir/$name"] ?: 0 }
            override fun list(dir: String, limit: Int): List<ImageEntry> {
                open()
                listHook(this)
                open()
                return files.keys.filter { it.substringBeforeLast('/') == dir }.map { val n = it.substringAfterLast('/'); ImageEntry(n, n !in notFiles, modified[it] ?: 0) }
            }
            override fun remove(dir: String, name: String) {
                open()
                if (removeFails) throw ImageFailureException(ImageFailure.SAVE_FAILED)
                files.remove("$dir/$name")
            }
            override fun close() { if (closeDelayMs > 0) Thread.sleep(closeDelayMs); closed = true; closedLatch.countDown() }
        }

        fun channel(): Channel = Channel().also { opened.incrementAndGet(); channels.add(it) }
    }

    private class Target(val device: Device) : ImageTarget {
        @Volatile var current = true
        @Volatile var afterUploadCheck: () -> Unit = {}
        val checks = AtomicInteger()
        val pastes = CopyOnWriteArrayList<Pair<String, Boolean>>()
        @Volatile var pasteResult: Boolean = true
        @Volatile var pasteThrows = false
        @Volatile var openHook: (track: (Closeable) -> Unit, opening: Int) -> Unit = { _, _ -> }
        @Volatile var openThrows: Throwable? = null
        override fun open(track: (Closeable) -> Unit): RemoteImages {
            openHook(track, device.opened.get())
            openThrows?.let { throw it }
            return device.channel()
        }
        override fun stillCurrent(): Boolean { if (checks.incrementAndGet() > 2) afterUploadCheck(); return current }
        override fun paste(text: String, enter: Boolean): Boolean {
            pastes += text to enter
            if (pasteThrows) throw IllegalStateException("boom")
            return pasteResult
        }
    }

    private val jpeg = EncodedImage(ByteArray(1000) { 7 }, ImageFormat.JPEG)
    private val at = LocalDateTime.of(2026, 11, 12, 1, 30)

    private fun job(target: ImageTarget, states: MutableList<ImageInsertState> = CopyOnWriteArrayList(), encode: () -> EncodedImage = { jpeg }) =
        ImageInsertJob(target, { _ -> encode() }, { states += it }, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined, closeWith = { it.close() }, cleanupRunner = { it.run() }, removalMs = 2000, cleanupMs = 2000)

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(15_000) { block() } }

    @Test fun insertsTheAbsolutePathOnceWithoutEnter() {
        val device = Device()
        val target = Target(device)
        val states = CopyOnWriteArrayList<ImageInsertState>()
        val outcome = blocking { job(target, states).run() }
        assertEquals(ImageInsertOutcome.Inserted, outcome)
        assertEquals(1, target.pastes.size)
        val (text, enter) = target.pastes.single()
        assertFalse("no Enter", enter)
        assertTrue(text, Regex("/home/u/\\.cache/frp-jump/images/img-20261112-0130-[a-z0-9]{4}\\.jpg").matches(text))
        assertTrue(device.files[text]!!.contentEquals(jpeg.bytes))
        assertEquals(ImageInsertState.Encoding, states.first())
        assertEquals(ImageInsertState.Uploading(1000, 1000), states.last())
    }

    @Test fun severalPathsAreOnePasteJoinedBySingleSpacesNeverNewlines() {
        assertEquals("/a/1.jpg", joinImagePaths(listOf("/a/1.jpg")))
        assertEquals("/a/1.jpg /a/2.png /a/3.jpg", joinImagePaths(listOf("/a/1.jpg", "/a/2.png", "/a/3.jpg")))
        assertFalse(joinImagePaths(listOf("/a/1.jpg", "/a/2.png")).contains('\n'))
        assertEquals("", joinImagePaths(emptyList()))
    }

    @Test fun aPngIsSentAsPng() {
        val target = Target(Device())
        blocking { job(target, encode = { EncodedImage(ByteArray(10), ImageFormat.PNG) }).run() }
        assertTrue(target.pastes.single().first.endsWith(".png"))
    }

    @Test fun anEncodingFailureOpensNothingAndInsertsNothing() {
        val device = Device()
        val target = Target(device)
        val outcome = blocking { job(target, encode = { throw ImageFailureException(ImageFailure.UNREADABLE) }).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.UNREADABLE), outcome)
        assertEquals(0, device.opened.get())
        assertTrue(target.pastes.isEmpty())
    }

    @Test fun aKnownRefusalOfTheFolderInsertsNothing() {
        val device = Device().apply { prepareFails = ImageFailure.FOLDER_UNSAFE }
        val target = Target(device)
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.FOLDER_UNSAFE), blocking { job(target).run() })
        assertTrue(device.files.isEmpty())
        assertTrue(target.pastes.isEmpty())
    }

    @Test fun aNameClashIsRetriedWithAnotherName() {
        val device = Device().apply { clashes = 3 }
        val target = Target(device)
        assertEquals(ImageInsertOutcome.Inserted, blocking { job(target).run() })
        assertEquals(1, device.files.size)
    }

    @Test fun clashesWithoutEndInsertNothing() {
        val device = Device().apply { clashes = 99 }
        val target = Target(device)
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.SAVE_FAILED), blocking { job(target).run() })
        assertTrue(target.pastes.isEmpty())
        assertTrue(device.files.isEmpty())
    }

    @Test fun aTabThatChangedDuringEncodingGetsNothing() {
        val device = Device()
        val target = Target(device)
        val outcome = blocking { job(target, encode = { target.current = false; jpeg }).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), outcome)
        assertEquals(0, device.opened.get())
    }

    @Test fun aTabThatChangedDuringTheUploadGetsNoPaste() {
        val device = Device()
        val target = Target(device)
        device.createHook = { target.current = false }
        val outcome = blocking { job(target).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), outcome)
        assertTrue("nothing pasted into a tab that is no longer the one asked", target.pastes.isEmpty())
        // The finished file stays: cleaned by age, as the contract says.
        assertEquals(1, device.files.size)
    }

    @Test fun aRefusedPasteIsFailureAndKeepsTheFile() {
        val device = Device()
        val target = Target(device).apply { pasteResult = false }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), blocking { job(target).run() })
        assertEquals(1, device.files.size)
    }

    @Test fun aPasteThatThrowsIsInsertRefused() {
        val target = Target(Device()).apply { pasteThrows = true }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.INSERT_REFUSED), blocking { job(target).run() })
    }

    @Test fun oldOwnFilesGoAndNothingElse() {
        val device = Device()
        val d = device.dir
        for ((name, age) in listOf("img-20260101-0000-aaaa.jpg" to 7200L, "img-20260101-0000-bbbb.png" to 3500L, "img-20260101-0000-cccc.gif" to 99_999L, "notes.txt" to 99_999L)) {
            device.files["$d/$name"] = ByteArray(1); device.modified["$d/$name"] = 100_000L - age
        }
        device.files["$d/img-20260101-0000-dddd.jpg"] = ByteArray(1)
        device.modified["$d/img-20260101-0000-dddd.jpg"] = 1
        device.notFiles += "img-20260101-0000-dddd.jpg"
        val target = Target(device)
        assertEquals(ImageInsertOutcome.Inserted, blocking { job(target).run() })
        val left = device.files.keys.map { it.substringAfterLast('/') }.toSet()
        assertFalse("old own file is deleted", "img-20260101-0000-aaaa.jpg" in left)
        assertTrue("a recent own file stays", "img-20260101-0000-bbbb.png" in left)
        assertTrue("a foreign extension stays", "img-20260101-0000-cccc.gif" in left)
        assertTrue("notes.txt" in left)
        assertTrue("a link or folder with an own name stays", "img-20260101-0000-dddd.jpg" in left)
        assertEquals(target.pastes.single().first.substringAfterLast('/'), left.single { it.startsWith("img-20261112") })
    }

    @Test fun aCleanupThatFailsDoesNotFailTheUpload() {
        val device = Device().apply { removeFails = true }
        val d = device.dir
        device.files["$d/img-20260101-0000-aaaa.jpg"] = ByteArray(1)
        device.modified["$d/img-20260101-0000-aaaa.jpg"] = 1
        val target = Target(device)
        assertEquals(ImageInsertOutcome.Inserted, blocking { job(target).run() })
        assertEquals(1, target.pastes.size)
    }

    @Test fun aWriteThatFailsWithTheConnectionUpIsNotSavedAndRemovesWhatWasMade() {
        val device = Device().apply { failMidWrite = true }
        val target = Target(device)
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.SAVE_FAILED), blocking { job(target).run() })
        assertTrue(device.files.isEmpty())
        assertTrue(target.pastes.isEmpty())
    }

    @Test fun aConnectionLostMidWriteSaysSoAndRemovesWhatWasMade() {
        val device = Device().apply { failMidWrite = true }
        val target = Target(device)
        device.createHook = { target.current = false }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), blocking { job(target).run() })
        assertTrue(target.pastes.isEmpty())
    }

    @Test fun cancelDuringTheUploadClosesTheChannelRemovesThePartFileAndInsertsNothing() {
        val device = Device()
        val target = Target(device)
        val inWrite = CountDownLatch(1)
        device.createHook = { channel ->
            inWrite.countDown()
            // Blocked in the write until the channel is closed, as a socket read is.
            channel.closedLatch.await(10, TimeUnit.SECONDS)
        }
        val cancelled = runBlocking {
            val job = async(Dispatchers.Default) { job(target).run() }
            assertTrue(inWrite.await(5, TimeUnit.SECONDS))
            job.cancel()
            awaitCancelled(job)
        }
        assertTrue(cancelled)
        assertTrue("the job's own channel was closed", device.channels.first().closed)
        assertTrue("the partial file is gone", device.files.isEmpty())
        assertTrue(target.pastes.isEmpty())
        assertTrue("removal used a channel of its own", device.opened.get() >= 2)
        assertTrue("and left none open", device.channels.all { it.closed })
    }

    @Test fun cancelDuringEncodingMakesNothing() {
        val device = Device()
        val target = Target(device)
        val encoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = runBlocking {
            val job = async(Dispatchers.Default) { job(target, encode = { encoding.countDown(); release.await(5, TimeUnit.SECONDS); jpeg }).run() }
            assertTrue(encoding.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            awaitCancelled(job)
        }
        assertTrue(cancelled)
        assertEquals(0, device.opened.get())
        assertTrue(target.pastes.isEmpty())
    }

    @Test fun cancelAfterTheUploadButBeforeTheInsertRemovesTheFile() {
        val device = Device()
        val target = Target(device)
        lateinit var self: Job
        target.afterUploadCheck = { self.cancel() }
        val cancelled = runBlocking {
            self = async(Dispatchers.Default, start = CoroutineStart.LAZY) { job(target).run() }
            self.start()
            try { withTimeout(10_000) { self.join() }; self.isCancelled } catch (e: CancellationException) { true }
        }
        assertTrue(cancelled)
        assertTrue("the finished file is removed when nothing was inserted", device.files.isEmpty())
        assertTrue(target.pastes.isEmpty())
    }

    // ---- fix pass: unexpected throwables, opening, hidden tabs, bounds ----

    @Test fun runningOutOfMemoryWhileEncodingIsTooLargeAndAnythingElseIsUnreadable() {
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.TOO_LARGE), blocking { job(Target(Device()), encode = { throw OutOfMemoryError() }).run() })
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.UNREADABLE), blocking { job(Target(Device()), encode = { throw IllegalStateException("x") }).run() })
    }

    @Test fun anIllegalStateFromTheOpeningIsNotAvailableWhileTheConnectionIsUp() {
        val device = Device()
        val target = Target(device).apply { openThrows = IllegalStateException("Not connected") }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.NOT_AVAILABLE), blocking { job(target).run() })
        assertTrue(target.pastes.isEmpty())
    }

    @Test fun anIllegalStateFromTheOpeningIsConnectionLostOnlyWhenTheConnectionIsGone() {
        val device = Device()
        val target = Target(device)
        target.openThrows = IllegalStateException("Not connected")
        target.openHook = { _, _ -> target.current = false }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), blocking { job(target, encode = { jpeg }).run() })
    }

    @Test fun anythingUnexpectedAfterTheOpeningIsAFailureNotACrash() {
        val device = Device().apply { prepareThrows = IllegalArgumentException("odd") }
        val outcome = blocking { job(Target(device)).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.SAVE_FAILED), outcome)
    }

    @Test fun aTabThatIsNoLongerShownGetsNoPaste() {
        val device = Device()
        val target = Target(device)
        var shown = true
        device.createHook = { shown = false }
        val outcome = blocking { job(target.onlyWhile { shown }).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), outcome)
        assertTrue("the path is not pasted into a hidden tab", target.pastes.isEmpty())
    }

    @Test fun aHiddenTabFailsBeforeAnythingIsOpened() {
        val device = Device()
        val outcome = blocking { job(Target(device).onlyWhile { false }).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), outcome)
        assertEquals(0, device.opened.get())
    }

    @Test fun aCleanupThatHangsNeverDelaysThePathAndIsClosedAtItsDeadline() {
        val device = Device()
        val d = device.dir
        device.files["$d/img-20260101-0000-aaaa.jpg"] = ByteArray(1)
        device.modified["$d/img-20260101-0000-aaaa.jpg"] = 1
        // The listing never answers until its channel is closed, as a hung server would.
        device.listHook = { channel -> channel.closedLatch.await(20, TimeUnit.SECONDS) }
        val target = Target(device)
        val started = System.nanoTime()
        // The real background runner and closer: the cleanup is on a thread of its own.
        val outcome = blocking { ImageInsertJob(target, { _ -> jpeg }, {}, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined, cleanupMs = 400).run() }
        assertEquals(ImageInsertOutcome.Inserted, outcome)
        assertTrue("the path did not wait for the cleanup", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(1, target.pastes.size)
        // The cleanup's channel is closed at its deadline, not left hanging.
        waitFor("the hung cleanup's channel to be closed") { device.channels.first().closed }
        assertTrue("the old file was not judged", device.files.containsKey("$d/img-20260101-0000-aaaa.jpg"))
    }

    @Test fun theCleanupDeadlineHandsTheChannelCloseToTheCloserInsteadOfClosingItOnTheWatchdogThread() {
        val device = Device()
        val d = device.dir
        device.files["$d/img-20260101-0000-aaaa.jpg"] = ByteArray(1)
        device.modified["$d/img-20260101-0000-aaaa.jpg"] = 1
        device.listHook = { channel -> channel.closedLatch.await(20, TimeUnit.SECONDS) }
        val closedVia = CopyOnWriteArrayList<Closeable>()
        val target = Target(device)
        val outcome = blocking { ImageInsertJob(target, { _ -> jpeg }, {}, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined, cleanupMs = 300, closeWith = { closedVia += it; it.close() }).run() }
        assertEquals(ImageInsertOutcome.Inserted, outcome)
        waitFor("the watchdog to close the cleanup's channel through the closer") { closedVia.any { it === device.channels.first() } }
    }

    @Test fun theCleanupFinishesOnItsOwnThreadAfterTheJobHasEnded() {
        val device = Device()
        val d = device.dir
        device.files["$d/img-20260101-0000-aaaa.jpg"] = ByteArray(1)
        device.modified["$d/img-20260101-0000-aaaa.jpg"] = 1
        // A slow listing: the job is over before it answers, and the channel must still be open for it.
        device.listHook = { Thread.sleep(1_500) }
        val target = Target(device)
        val outcome = blocking { ImageInsertJob(target, { _ -> jpeg }, {}, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined).run() }
        assertEquals(ImageInsertOutcome.Inserted, outcome)
        assertTrue("the job did not wait for the listing", device.files.containsKey("$d/img-20260101-0000-aaaa.jpg"))
        waitFor("the old file to be removed by the cleanup") { !device.files.containsKey("$d/img-20260101-0000-aaaa.jpg") }
        waitFor("then the channel to be closed") { device.channels.first().closed }
    }

    @Test fun aSlowCloseNeverDelaysTheEndOfTheJob() {
        // Closing an sshj channel can wait 30 s for the peer; the job (and its tab's guard) must not.
        val device = Device().apply { prepareFails = ImageFailure.FOLDER_UNSAFE; closeDelayMs = 3_000 }
        val started = System.nanoTime()
        val outcome = blocking { ImageInsertJob(Target(device), { _ -> jpeg }, {}, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.FOLDER_UNSAFE), outcome)
        assertTrue("the job ended without waiting for the close", (System.nanoTime() - started) / 1_000_000 < 1_500)
        waitFor("the channel to be closed in the background") { device.channels.first().closed }
    }

    private fun waitFor(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < until)
            Thread.sleep(20)
        }
    }

    @Test fun aTabThatIsGoneAtTheStartIsNotEvenEncoded() {
        var encoded = false
        val device = Device()
        val outcome = blocking { job(Target(device).onlyWhile { false }, encode = { encoded = true; jpeg }).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.CONNECTION_LOST), outcome)
        assertFalse("nothing was read or encoded for a tab that is gone", encoded)
        assertEquals(0, device.opened.get())
    }

    @Test fun aFileWithNoTimeIsNeverCleanedUp() {
        val device = Device()
        val d = device.dir
        device.files["$d/img-20260101-0000-none.jpg"] = ByteArray(1)
        val target = Target(device)
        assertEquals(ImageInsertOutcome.Inserted, blocking { job(target).run() })
        assertTrue(device.files.containsKey("$d/img-20260101-0000-none.jpg"))
    }

    @Test fun cancelWhileTheChannelIsBeingOpenedClosesItAndEndsAtOnce() {
        val device = Device()
        val target = Target(device)
        val opening = CountDownLatch(1)
        val closed = CountDownLatch(1)
        target.openHook = { track, _ ->
            val gate = CountDownLatch(1)
            track(Closeable { closed.countDown(); gate.countDown() })
            opening.countDown()
            // The opening blocks until what it was handed is closed.
            gate.await(20, TimeUnit.SECONDS)
            throw IOException("closed")
        }
        val started = System.nanoTime()
        val cancelled = runBlocking {
            val running = async(Dispatchers.Default) { job(target).run() }
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            running.cancel()
            awaitCancelled(running)
        }
        assertTrue(cancelled)
        assertTrue("the channel being opened was closed", closed.await(1, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 8_000)
        assertTrue(target.pastes.isEmpty())
    }

    @Test fun theRemovalBoundHoldsEvenWhenThePartFilesChannelCannotBeOpened() {
        val device = Device()
        val target = Target(device)
        val inWrite = CountDownLatch(1)
        device.createHook = { channel -> inWrite.countDown(); channel.closedLatch.await(20, TimeUnit.SECONDS) }
        // The second opening (for the removal) hangs until what it was handed is closed.
        target.openHook = { track, openedSoFar ->
            if (openedSoFar >= 1) {
                val gate = CountDownLatch(1)
                track(Closeable { gate.countDown() })
                gate.await(30, TimeUnit.SECONDS)
                throw IOException("closed")
            }
        }
        val started = System.nanoTime()
        val cancelled = runBlocking {
            val running = async(Dispatchers.Default) { ImageInsertJob(target, { _ -> jpeg }, {}, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined, closeWith = { it.close() }, cleanupRunner = { it.run() }, removalMs = 500).run() }
            assertTrue(inWrite.await(5, TimeUnit.SECONDS))
            running.cancel()
            awaitCancelled(running)
        }
        assertTrue(cancelled)
        assertTrue("the removal gave up at its bound, not at the hung open", (System.nanoTime() - started) / 1_000_000 < 8_000)
    }

    @Test fun cancelWhilePreparingClosesTheStreamBeingRead() {
        val device = Device()
        val target = Target(device)
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelled = runBlocking {
            val running = async(Dispatchers.Default) {
                ImageInsertJob(target, { track ->
                    val gate = CountDownLatch(1)
                    track(Closeable { closed.countDown(); gate.countDown() })
                    reading.countDown()
                    gate.await(20, TimeUnit.SECONDS)
                    throw IOException("closed")
                }, {}, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined, closeWith = { it.close() }, cleanupRunner = { it.run() }).run()
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            running.cancel()
            awaitCancelled(running)
        }
        assertTrue(cancelled)
        assertTrue("the picture's stream was closed", closed.await(1, TimeUnit.SECONDS))
        assertEquals(0, device.opened.get())
    }

    @Test fun aThrowableFromAnywhereIsAFailureNotACrash() {
        val device = Device()
        // Not inside any step's own handling: the tab's check itself blows up.
        val target = object : ImageTarget {
            override fun open(track: (Closeable) -> Unit): RemoteImages = device.channel()
            override fun stillCurrent(): Boolean = throw UnsupportedOperationException("odd")
            override fun paste(text: String, enter: Boolean) = true
        }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.SAVE_FAILED), blocking { job(target).run() })
        // A progress callback that throws is the same.
        val outcome = blocking { ImageInsertJob(Target(Device()), { _ -> jpeg }, { throw IllegalArgumentException("ui") }, now = { at }, random = Random(3), insertOn = Dispatchers.Unconfined, closeWith = { it.close() }, cleanupRunner = { it.run() }).run() }
        assertEquals(ImageInsertOutcome.Failed(ImageFailure.SAVE_FAILED), outcome)
    }

    /**
     * Awaits [running] after it was cancelled. A timeout is also a CancellationException, so the time is what proves the
     * cancel took effect: it must end well before any hook's long wait, not at the timeout.
     */
    private suspend fun awaitCancelled(running: kotlinx.coroutines.Deferred<*>, limitMs: Long = 7_000): Boolean {
        val started = System.nanoTime()
        val cancelled = try { withTimeout(20_000) { running.await() }; false } catch (e: CancellationException) { true }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("ended after $ms ms: the cancel did not take effect at once", ms < limitMs)
        return cancelled
    }
}
