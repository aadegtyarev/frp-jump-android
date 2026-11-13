package ru.adsrv.frpjump.ui.files

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.files.PickedFile
import ru.adsrv.frpjump.files.RemoteEntry
import ru.adsrv.frpjump.files.EntryType
import ru.adsrv.frpjump.files.RowJobs
import ru.adsrv.frpjump.files.TransferJobs
import ru.adsrv.frpjump.files.FilesTarget
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.store.TextStorage
import java.io.IOException

/** Real leave/resume/upload entry points, no SFTP, profiles, credentials, or phone-provider queries. */
@RunWith(AndroidJUnit4::class)
class FilesSessionVisitTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as FrpJumpApp
    private val sessions = mutableListOf<FilesSession>()
    private val releases = mutableListOf<CountDownLatch>()
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun cleanUp() {
        releases.forEach { it.countDown() }
        main { sessions.forEach { it.close() } }
        workers.cancel()
    }

    @Test
    fun delayedLeaveCleanupDoesNotCancelTransferStartedAfterResume() {
        val dispatcher = QueuedDispatcher()
        val session = newSession(dispatcher)
        val transfers = field<TransferJobs>(session, "transfers")
        val old = transfer(transfers)
        main { session.leave() }
        assertEquals("Actual leave cleanup is held before it can snapshot jobs", 1, dispatcher.size)
        main { session.resume() }
        val current = transfer(transfers)
        try {
            dispatcher.runNext()
            assertTrue("Leaving visit's transfer is cancelled", old.isCancelled)
            assertTrue("Old visit cleanup must not cancel the new visit's transfer", current.isActive)
        } finally {
            workers.cancel()
            dispatcher.finish()
        }
    }

    @Test
    fun lateIoProgressDoesNotRestoreARowClearedOnLeave() = progressAfterLeave(resume = false)

    @Test
    fun lateIoProgressDoesNotRestoreARowAfterResume() = progressAfterLeave(resume = true)

    @Test
    fun currentVisitIoProgressStillUpdatesItsRow() {
        val session = newSession()
        val report = reporter(session)
        runBlocking { withContext(Dispatchers.IO) { report(3, 7) } }
        assertEquals(3L, session.state.value.downloads[ENTRY.path]?.done)
    }

    private fun progressAfterLeave(resume: Boolean) {
        val dispatcher = QueuedDispatcher()
        val session = newSession(dispatcher)
        val report = reporter(session)
        main { session.leave(); if (resume) session.resume() }
        try {
            // The old row owner has not unwound yet, as with a stalled non-suspending read.
            // Deliver the real progress callback from IO, not a copy of its implementation.
            runBlocking { withContext(Dispatchers.IO) { report(3, 7) } }
            assertTrue("Late IO report must not restore the old visit's row", session.state.value.downloads.isEmpty())
        } finally {
            workers.cancel()
            dispatcher.finish()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reporter(session: FilesSession): (Long, Long) -> Unit {
        val job = transfer(field(session, "transfers"))
        val rows = field<RowJobs<String>>(session, "downloadJobs")
        rows.put(ENTRY.path, job)
        val mine: () -> Boolean = { rows.owns(ENTRY.path, job) }
        return main {
            val state = field<MutableStateFlow<FilesUiState>>(session, "_state")
            state.value = state.value.copy(downloads = mapOf(ENTRY.path to Transfer(ENTRY.name, 0, ENTRY.size)))
            FilesSession::class.java.declaredMethods.single { it.name == "downloadProgress" }
                .apply { isAccessible = true }.invoke(session, ENTRY, TransferKind.DOWNLOAD, mine) as (Long, Long) -> Unit
        }
    }

    @Test
    fun resumedVisitCanPrepareUploadWhileOldMetadataAndCleanupAreStillBlocked() {
        val dispatcher = QueuedDispatcher()
        val entered = List(2) { CountDownLatch(1) }
        val release = List(2) { CountDownLatch(1).also { releases += it } }
        val calls = AtomicInteger()
        val session = newSession(dispatcher, pick = {
            val index = calls.getAndIncrement()
            entered[index].countDown()
            check(release[index].await(5, TimeUnit.SECONDS))
            PickedFile(it, if (index == 0) "old.txt" else "new.txt", 7)
        })
        ready(session)
        main { session.upload(listOf(URI)) }
        assertTrue(entered[0].await(3, TimeUnit.SECONDS))
        val scopeJob = field<CoroutineScope>(session, "scope").coroutineContext[Job]!!
        val old = scopeJob.children.single()
        main { session.leave(); session.resume(); session.upload(listOf(URI)) }
        assertTrue(entered[1].await(3, TimeUnit.SECONDS))
        val current = scopeJob.children.single { it !== old }
        try {
            dispatcher.runNext()
            assertTrue(old.isCancelled)
            assertTrue("Old cleanup only joins its own snapshot", current.isActive)
        } finally {
            release.forEach { it.countDown() }
            runBlocking { withTimeout(3_000) { old.join(); current.join() } }
            dispatcher.finish()
        }
        assertEquals(listOf("new.txt"), session.state.value.uploads.map { it.transfer.name })
    }

    @Test
    fun freshVisitProgressWorksAndOldReporterCannotOverwriteIt() {
        val dispatcher = QueuedDispatcher()
        val session = newSession(dispatcher)
        val old = reporter(session)
        main { session.leave(); session.resume() }
        val current = reporter(session)
        try {
            runBlocking { withContext(Dispatchers.IO) { current(5, 7); old(3, 7) } }
            assertEquals(5L, session.state.value.downloads[ENTRY.path]?.done)
        } finally {
            workers.cancel()
            dispatcher.finish()
        }
    }

    @Test
    fun closeIsFinalEvenIfResumeAndUploadCallbacksArriveLater() {
        val calls = AtomicInteger()
        val session = newSession(pick = { calls.incrementAndGet(); PickedFile(it, "unexpected.txt", 1) })
        ready(session)
        val old = reporter(session)
        main { session.close(); session.resume(); session.upload(listOf(URI)) }
        runBlocking { withContext(Dispatchers.IO) { old(3, 7) } }
        instrumentation.waitForIdleSync()
        assertTrue(session.state.value.uploads.isEmpty())
        assertTrue(session.state.value.downloads.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun metadataReturningAfterLeaveDoesNotRecreateUploadRows() = lateMetadata(resume = false)

    @Test
    fun metadataReturningAfterResumeDoesNotStartAnOldVisitUpload() = lateMetadata(resume = true)

    @Test
    fun metadataForCurrentVisitStillQueuesThePickedFile() {
        val session = newSession(pick = { PickedFile(it, "synthetic.txt", 7) })
        ready(session)
        main { session.upload(listOf(URI)) }
        joinPreparation(session)
        assertEquals(listOf("synthetic.txt"), session.state.value.uploads.map { it.transfer.name })
        assertEquals(listOf("/fixture"), session.state.value.uploads.map { it.dir })
    }

    private class Disk(var text: String? = null, @Volatile var fail: Boolean = false) : TextStorage {
        override fun read() = text
        override fun write(text: String) {
            if (fail) throw IOException("disk full")
            this.text = text
        }
        override fun delete() { text = null }
    }

    @Test
    fun failedTrustSaveShowsTheErrorTrustsNothingAndCanBeRetried() = failedHostKeySave(forget = false)

    @Test
    fun failedForgetSaveShowsTheErrorAndKeepsTheOldPin() = failedHostKeySave(forget = true)

    private fun failedHostKeySave(forget: Boolean) {
        val old = PinnedHostKey("synthetic-type", "synthetic-old")
        val presented = PinnedHostKey("synthetic-type", "synthetic-presented")
        val disk = Disk()
        val keys = HostKeyStore(disk)
        if (forget) keys.pin("dev", 22, old)
        disk.fail = true
        val session = main {
            FilesSession(app, ABSENT_TARGET, Dispatchers.IO, hostKeysOverride = keys).also { sessions += it }
        }
        main {
            FilesSession::class.java.getDeclaredField("target").apply { isAccessible = true }
                .set(session, FilesTarget("synthetic-profile", "dev", 22, "root", SshAuth.Key))
            field<MutableStateFlow<FilesUiState>>(session, "_state").value = FilesUiState(
                status = if (forget) FilesStatus.HostKeyChanged(old, presented) else FilesStatus.HostKeyUnknown(presented),
            )
            if (forget) session.forgetOldHostKey() else session.trustHostKey()
        }
        runBlocking {
            withTimeout(5_000) { while (session.state.value.status !is FilesStatus.Failed) delay(10) }
        }
        val failed = session.state.value.status as FilesStatus.Failed
        assertTrue(failed.message, failed.message.contains("disk full"))
        assertEquals(if (forget) old else null, keys.pinned("dev", 22))
        // The owner can retry: the same answer works once the disk does.
        disk.fail = false
        main {
            field<MutableStateFlow<FilesUiState>>(session, "_state").value = FilesUiState(status = FilesStatus.HostKeyUnknown(presented))
            session.trustHostKey()
        }
        runBlocking { withTimeout(5_000) { while (keys.pinned("dev", 22) != presented) delay(10) } }
    }

    private fun lateMetadata(resume: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases += it }
        val session = newSession(pick = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test metadata gate timed out" }
            PickedFile(it, "synthetic.txt", 7)
        })
        ready(session)
        main { session.upload(listOf(URI)) }
        assertTrue("Preparation entered the blocking metadata boundary", entered.await(3, TimeUnit.SECONDS))
        val preparation = field<CoroutineScope>(session, "scope").coroutineContext[Job]!!.children.single()
        main {
            session.leave()
            if (resume) session.resume()
        }
        assertTrue(session.state.value.uploads.isEmpty())
        release.countDown()
        runBlocking { withTimeout(3_000) { preparation.join() } }
        assertTrue("Old visit's metadata must not publish queued uploads", session.state.value.uploads.isEmpty())
    }

    private fun newSession(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        pick: (Uri) -> PickedFile = { error("Unexpected metadata query") },
    ): FilesSession = main {
        // No target/runtime: constructor never starts a tunnel observer or connection.
        // Tests seed only transfer eligibility; runUploads cannot reach a connector without a target.
        check(app.fileTargets.get(ABSENT_TARGET) == null)
        FilesSession(app, ABSENT_TARGET, dispatcher, pick).also { sessions += it }
    }

    private fun ready(session: FilesSession) = main {
        field<MutableStateFlow<FilesUiState>>(session, "_state").value =
            FilesUiState(path = "/fixture", status = FilesStatus.Ready)
        FilesSession::class.java.getDeclaredField("localPort").apply { isAccessible = true }.set(session, 12345)
    }

    private fun joinPreparation(session: FilesSession) {
        val jobs = field<CoroutineScope>(session, "scope").coroutineContext[Job]!!.children.toList()
        runBlocking { withTimeout(3_000) { jobs.forEach { it.join() } } }
        instrumentation.waitForIdleSync()
    }

    private fun transfer(transfers: TransferJobs): Job {
        val entered = CountDownLatch(1)
        val job = workers.launch(start = TransferJobs.START) {
            entered.countDown()
            awaitCancellation()
        }
        transfers.track(job)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        return job
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(session: FilesSession, name: String): T =
        FilesSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session) as T

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        private val jobs = ConcurrentLinkedQueue<Job>()
        val size get() = queue.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            context[Job]?.let { jobs += it }
            queue += block
        }
        fun runNext() { checkNotNull(queue.poll()) { "No queued cleanup" }.run() }
        fun finish() = runBlocking {
            withTimeout(3_000) {
                while (jobs.any { !it.isCompleted }) {
                    while (true) (queue.poll() ?: break).run()
                    delay(1)
                }
            }
        }
    }

    private companion object {
        const val ABSENT_TARGET = Int.MAX_VALUE
        val URI: Uri = Uri.parse("content://synthetic.files/fixture.txt")
        val ENTRY = RemoteEntry("synthetic.txt", "/fixture/synthetic.txt", EntryType.FILE, size = 7)
    }
}
