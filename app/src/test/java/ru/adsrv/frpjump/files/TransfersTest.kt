package ru.adsrv.frpjump.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class TransfersTest {
    /** A download target on the "phone" that says whether it was thrown away. */
    private class Sink : DownloadSink {
        val out = ByteArrayOutputStream()
        var discarded = false

        override fun open(): OutputStream = out

        override fun discard() {
            discarded = true
        }
    }

    private val big = "x".repeat(300 * 1024)

    @Test
    fun `a finished download is kept`() = runBlocking {
        val sink = Sink()
        downloadInto(FakeFs().file("/d/a.txt", "hello"), "/d/a.txt", sink) { _, _ -> }
        assertEquals("hello", sink.out.toString())
        assertFalse(sink.discarded)
    }

    @Test
    fun `a download that fails half way is thrown away`() = runBlocking {
        val sink = object : DownloadSink {
            var discarded = false
            var written = 0

            override fun open(): OutputStream = object : OutputStream() {
                override fun write(b: Int) = Unit

                override fun write(b: ByteArray, off: Int, len: Int) {
                    written += len
                    if (written > 100_000) throw IOException("ENOSPC (No space left on device)")
                }
            }

            override fun discard() {
                discarded = true
            }
        }
        val failure = runCatching { downloadInto(FakeFs().file("/d/big", big), "/d/big", sink) { _, _ -> } }.exceptionOrNull()
        assertTrue(failure is PhoneSideException)
        assertTrue(sink.discarded)
    }

    @Test
    fun `a cancelled download (Back) is thrown away, not left truncated`() = runBlocking {
        val sink = Sink()
        lateinit var job: kotlinx.coroutines.Job
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            downloadInto(FakeFs().file("/d/big", big), "/d/big", sink) { done, _ -> if (done > 0) job.cancel() }
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(sink.discarded)
    }

    @Test
    fun `an upload lands under its name and leaves no part file`() = runBlocking {
        val fs = FakeFs().dir("/d")
        val name = uploadInto(fs, SlowInput("data"), 4, "/d", "a.txt", UploadNames(), "dev") { _, _ -> }
        assertEquals("a.txt", name)
        assertEquals("data", fs.text("/d/a.txt"))
        assertEquals(listOf("a.txt"), fs.names("/d"))
    }

    @Test
    fun `a taken name keeps both`() = runBlocking {
        val fs = FakeFs().file("/d/a.txt", "old")
        assertEquals("a (1).txt", uploadInto(fs, SlowInput("new"), 3, "/d", "a.txt", UploadNames(), "dev") { _, _ -> })
        assertEquals("old", fs.text("/d/a.txt"))
        assertEquals("new", fs.text("/d/a (1).txt"))
    }

    @Test
    fun `an upload that fails removes its part file and makes no file`() = runBlocking {
        val fs = FakeFs().dir("/d").apply { failWritesAfter = 50_000 }
        val failure = runCatching { uploadInto(fs, SlowInput(big), big.length.toLong(), "/d", "a.txt", UploadNames(), "dev") { _, _ -> } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(emptyList<String>(), fs.names("/d"))
    }

    @Test
    fun `an upload the phone cannot read removes its part file`() = runBlocking {
        val fs = FakeFs().dir("/d")
        val failure = runCatching { uploadInto(fs, SlowInput(big, failAfter = 10_000), big.length.toLong(), "/d", "a.txt", UploadNames(), "dev") { _, _ -> } }.exceptionOrNull()
        assertTrue(failure is PhoneSideException)
        assertEquals(emptyList<String>(), fs.names("/d"))
    }

    @Test
    fun `a cancelled upload removes its part file before it returns`() = runBlocking {
        val fs = FakeFs().dir("/d")
        lateinit var job: kotlinx.coroutines.Job
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            uploadInto(fs, SlowInput(big), big.length.toLong(), "/d", "a.txt", UploadNames(), "dev") { done, _ -> if (done > 0) job.cancel() }
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), fs.names("/d"))
    }

    @Test
    fun `a part file left by an interrupted upload is replaced, not appended to`() = runBlocking {
        val fs = FakeFs().file("/d/.a.txt.frpjump-part", "stale half")
        uploadInto(fs, SlowInput("fresh"), 5, "/d", "a.txt", UploadNames(), "dev") { _, _ -> }
        assertEquals("fresh", fs.text("/d/a.txt"))
        assertEquals(listOf("a.txt"), fs.names("/d"))
    }

    @Test
    fun `two uploads of one name at once get two names and both arrive whole`() = runBlocking {
        val fs = FakeFs().dir("/d")
        val names = UploadNames()
        val got = listOf("first", "second").map { text ->
            async(Dispatchers.IO) { text to uploadInto(fs, SlowInput(text.repeat(2000), slowMs = 2), 0, "/d", "a.txt", names, "dev") { _, _ -> } }
        }.awaitAll().toMap()
        assertEquals(setOf("a.txt", "a (1).txt"), got.values.toSet())
        got.forEach { (text, name) -> assertEquals(text.repeat(2000), fs.text("/d/$name")) }
        assertEquals(2, fs.names("/d").size)
    }

    @Test
    fun `the same name on two devices is not held against each other`() {
        val names = UploadNames()
        assertEquals("a.txt", names.reserve("dev1", "/d", "a.txt") { false })
        assertEquals("a.txt", names.reserve("dev2", "/d", "a.txt") { false })
        assertEquals("a (1).txt", names.reserve("dev1", "/d", "a.txt") { false })
        names.release("dev1", "/d", "a.txt")
        assertEquals("a.txt", names.reserve("dev1", "/d", "a.txt") { false })
    }

    @Test
    fun `a name taken just before the rename moves the upload to the next name, the other file untouched`() = runBlocking {
        val fs = FakeFs().dir("/d")
        var once = true
        fs.beforeRename = { _, to ->
            if (once && to == "/d/a.txt") {
                once = false
                fs.file("/d/a.txt", "someone else")
            }
        }
        val name = uploadInto(fs, SlowInput("mine"), 4, "/d", "a.txt", UploadNames(), "dev") { _, _ -> }
        assertEquals("a (1).txt", name)
        assertEquals("someone else", fs.text("/d/a.txt"))
        assertEquals("mine", fs.text("/d/a (1).txt"))
        assertEquals(2, fs.names("/d").size)
    }

    @Test
    fun `the connection closes only after cancelled transfers cleaned up`() = runBlocking {
        val jobs = TransferJobs()
        var cleaned = false
        jobs.track(
            launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    delay(10_000)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { delay(50) }
                    cleaned = true
                    throw e
                }
            },
        )
        delay(20)
        jobs.cancelAndJoin()
        assertTrue(cleaned)
    }

    @Test
    fun `nothing to wait for returns at once`() = runBlocking {
        TransferJobs().cancelAndJoin()
    }
}
