package ru.adsrv.frpjump.files

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real helpers, with blocking returns held until the test has revoked their job. */
@RunWith(Parameterized::class)
class TransferCommitTest(private val direction: String, private val boundary: String, private val empty: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}-empty={2}")
        fun cases(): List<Array<Any>> = buildList {
            for (empty in listOf(false, true)) {
                for (direction in listOf("upload", "download")) {
                    for (boundary in listOf("eof", "flush", "outputClose", "initialProgress", "finalProgress", "success"))
                        add(arrayOf(direction, boundary, empty))
                    if (!empty) for (boundary in listOf("data", "write")) add(arrayOf(direction, boundary, empty))
                }
                for (boundary in listOf("openRead", "sinkOpen", "inputClose")) add(arrayOf("download", boundary, empty))
                for (boundary in listOf("create", "reserve", "partStat", "partStatFresh", "partStatStale", "staleRemove", "renameRefusal", "clashExists", "retryReserve", "inFlightRename", "committedRename", "committedRenameThrows", "phoneInputClose", "exclusiveClash", "cleanupFailure"))
                    add(arrayOf("upload", boundary, empty))
                add(arrayOf("download", "cleanupFailure", empty))
            }
        }
    }

    @Test fun `cancellation respects the physical commit boundary and exact cleanup`() = runBlocking {
        val gate = Gate()
        fun hit(at: String) { if (at == boundary) gate.block() }
        val text = if (empty) "" else "payload"
        val base = FakeFs().dir("/d").file("/d/unrelated", "keep")
        val names = UploadNames()
        val root = Files.createTempDirectory("transfer-commit").toFile()
        val target = File(root, "owned")
        val other = File(root, "other").apply { writeText("keep") }
        var writes = 0
        var flushes = 0
        var renames = 0
        var creates = 0
        var opens = 0
        var inputClosed = false
        var outputClosed = false
        var discarded = 0
        var returned = false
        var failure: Throwable? = null
        var clashed = false
        var finalLookups = 0
        var retryLookups = 0
        if (boundary in listOf("staleRemove", "partStatStale")) base.file("/d/.a.txt.frpjump-part", "stale")
        if (boundary == "partStatFresh") base.file("/d/.a.txt.frpjump-part", "fresh", modified = 999)
        fun source(raw: InputStream) = object : FilterInputStream(raw) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { hit(if (it < 0) "eof" else "data") }
            override fun close() {
                super.close()
                inputClosed = true
                hit(if (direction == "upload") "phoneInputClose" else "inputClose")
            }
        }
        fun output(raw: OutputStream) = object : FilterOutputStream(raw) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                writes++
                hit("write")
            }
            override fun flush() { out.flush(); flushes++; hit("flush") }
            // Do not introduce FilterOutputStream's implicit extra flush on close.
            override fun close() { out.close(); outputClosed = true; hit("outputClose") }
        }
        val fs = object : RemoteFs by base {
            override fun openRead(path: String): RemoteRead {
                opens++
                val read = RemoteRead(source(ByteArrayInputStream(text.toByteArray())), text.length.toLong())
                hit("openRead")
                return read
            }
            override fun lstat(path: String): RemoteAttrs {
                if (path == "/d/a.txt") {
                    finalLookups++
                    if (finalLookups == 1) hit("reserve")
                    if (clashed) hit("clashExists")
                }
                if (clashed && path == "/d/a (1).txt") { retryLookups++; hit("retryReserve") }
                try { return base.lstat(path) } finally {
                    if (path == "/d/.a.txt.frpjump-part") {
                        hit("partStat")
                        hit("partStatFresh")
                        hit("partStatStale")
                    }
                }
            }
            override fun createExclusive(path: String): OutputStream {
                creates++
                if (boundary == "exclusiveClash") {
                    base.file(path, "theirs")
                    gate.block()
                }
                val out = output(base.createExclusive(path))
                hit("create")
                return out
            }
            override fun rename(from: String, to: String) {
                renames++
                if (!clashed && boundary in listOf("renameRefusal", "clashExists", "retryReserve")) {
                    base.file(to, "theirs")
                    clashed = true
                    hit("renameRefusal")
                    throw RemoteRefusal(Refusal.EXISTS)
                }
                hit("inFlightRename")
                base.rename(from, to)
                hit("committedRename")
                hit("committedRenameThrows")
                if (boundary == "committedRenameThrows") throw IOException("reply lost after remote commit")
            }
            override fun remove(path: String) {
                if (boundary == "cleanupFailure") throw IOException("synthetic remove refusal")
                base.remove(path)
                hit("staleRemove")
            }
        }
        val sink = object : DownloadSink {
            override fun open(): OutputStream {
                opens++
                val out = output(target.outputStream())
                hit("sinkOpen")
                return out
            }
            override fun discard() {
                discarded++
                if (boundary == "cleanupFailure") throw IOException("synthetic discard refusal")
                assertTrue(!target.exists() || target.delete())
            }
        }
        lateinit var job: Job
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                var reports = 0
                val progress: (Long, Long) -> Unit = { done, _ ->
                    reports++
                    if (boundary == "finalProgress" && reports > 1 && done == text.length.toLong()) gate.block()
                    if (boundary in listOf("cleanupFailure", "initialProgress") && reports == 1) gate.block()
                }
                if (direction == "upload") {
                    // Caller retains input ownership: this close is intentionally AFTER rename.
                    source(ByteArrayInputStream(text.toByteArray())).use {
                        uploadInto(fs, it, text.length.toLong(), "/d", "a.txt", names, "dev", nowSeconds = 1000, onProgress = progress)
                    }
                } else downloadInto(fs, "/d/source", sink, progress)
                returned = true
            } catch (e: Throwable) { failure = e }
        }
        try {
            job.start()
            if (boundary != "success") {
                assertTrue("boundary entered", gate.entered.await(5, TimeUnit.SECONDS))
                job.cancel()
                gate.release.countDown()
            }
            withTimeout(10_000) { job.join() }
            assertEquals("unrelated remote", "keep", base.text("/d/unrelated"))
            assertEquals("unrelated sink", "keep", other.readText())
            val committed = boundary in listOf("success", "inFlightRename", "committedRename", "committedRenameThrows", "phoneInputClose")
            if (direction == "upload") {
                if (committed) {
                    assertEquals(text, base.text("/d/a.txt"))
                    if (boundary == "committedRenameThrows") {
                        assertFalse("lost reply is not success", returned)
                        assertTrue(failure is IOException)
                    } else assertTrue("helper may return after an already committed request", returned)
                } else {
                    assertEquals("no preventable final publication", if (clashed) "theirs" else null, base.text("/d/a.txt"))
                    assertFalse("no retry publication", base.has("/d/a (1).txt"))
                    assertEquals("rename count", if (clashed) 1 else 0, renames)
                    assertFalse("no successful helper return", returned)
                    assertNotNull("failure observed", failure)
                }
                when (boundary) {
                    "exclusiveClash" -> assertEquals("theirs", base.text("/d/.a.txt.frpjump-part"))
                    "partStatFresh" -> assertEquals("fresh", base.text("/d/.a.txt.frpjump-part"))
                    "partStatStale" -> assertEquals("stale", base.text("/d/.a.txt.frpjump-part"))
                    "cleanupFailure" -> assertTrue("failed cleanup remains physical, not success", base.has("/d/.a.txt.frpjump-part"))
                    else -> assertFalse("own part cleaned", base.has("/d/.a.txt.frpjump-part"))
                }
                assertEquals("initial reservation released", "a.txt", names.reserve("dev", "/d", "a.txt") { false })
                names.release("dev", "/d", "a.txt")
                assertEquals("retry reservation released", "a (1).txt", names.reserve("dev", "/d", "a (1).txt") { false })
                if (boundary in listOf("reserve", "partStat", "partStatFresh", "partStatStale", "staleRemove")) assertEquals("no later create", 0, creates)
            } else {
                if (committed) { assertTrue(returned); assertEquals(text, target.readText()); assertEquals(0, discarded) }
                else {
                    assertEquals("physical sink state", boundary == "cleanupFailure", target.exists())
                    assertEquals("exact sink discarded once", 1, discarded)
                    assertFalse("no successful helper return", returned)
                    assertNotNull(failure)
                }
                assertTrue("remote input closed even after late acquisition", inputClosed)
                if (boundary == "openRead") assertEquals("no sink acquisition", 1, opens)
            }
            if (boundary in listOf("data", "create", "sinkOpen", "cleanupFailure", "initialProgress")) assertEquals("no subsequent write", 0, writes)
            if (boundary in listOf("data", "eof", "write")) assertEquals("no subsequent flush", 0, flushes)
            if (boundary == "create" || boundary == "sinkOpen") assertTrue("late output closed", outputClosed)
        } finally {
            gate.release.countDown()
            job.cancel()
            withTimeout(10_000) { job.join() }
            root.deleteRecursively()
        }
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test gate timed out" }
        }
    }
}
