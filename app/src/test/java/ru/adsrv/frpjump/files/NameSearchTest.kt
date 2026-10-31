package ru.adsrv.frpjump.files

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NameSearchTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("namesearch").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun globEscapeMakesEveryMetacharacterLiteral() {
        assertEquals("a\\*b\\?\\[c\\]\\\\d", globEscape("a*b?[c]\\d"))
        assertEquals("plain-name.txt", globEscape("plain-name.txt"))
    }

    @Test
    fun scriptQuotesForShOnlyAndTheLoginShellSeesNoUserText() {
        // The login shell (fish too) parses only this; the script goes on the input.
        assertEquals("sh -s", FIND_EXEC)
        val script = findScript("/mnt/my data", "it's", hidden = true)
        assertTrue(script, script.startsWith("printf '%s\\000' $SEARCH_SENTINEL; "))
        assertTrue(script, script.contains("find '/mnt/my data' -xdev -iname '*it'\\''s*' -print0"))
        assertFalse(script, script.contains("-prune"))
        assertTrue(script, script.endsWith("\n") && script.count { it == '\n' } == 1)
        assertTrue(findScript("/x", "a", hidden = false).contains("-xdev ! -path '/x' -name '.*' -prune -o -iname"))
    }

    @Test
    fun matchesTheFolderNamesIgnoringCase() {
        assertTrue(nameMatches("ReadMe.md", " readme "))
        assertFalse(nameMatches("notes.txt", "readme"))
    }

    @Test
    fun relativePathsBelowTheRoot() {
        assertEquals("etc/wb-rules", relativeTo("/", "/etc/wb-rules"))
        assertEquals("a/b", relativeTo("/mnt/data", "/mnt/data/a/b"))
        assertNull(relativeTo("/mnt/data", "/mnt/data"))
        assertNull(relativeTo("/mnt/data", "/mnt/database/x"))
    }

    @Test
    fun recordsSplitAcrossChunks() {
        val records = NulRecords()
        val bytes = "/r/a/один\u0000/r/b/tw".toByteArray()
        assertEquals(listOf("/r/a/один"), records.feed(bytes, bytes.size))
        val rest = "o\u0000".toByteArray()
        assertEquals(listOf("/r/b/two"), records.feed(rest, rest.size))
    }

    @Test
    fun hitsComeAfterTheSentinelOnly() {
        val hits = Hits("/r", cap = 10)
        val other = "This account is currently not available.\n/r/a/x\u0000".toByteArray()
        assertEquals(emptyList<Found>(), hits.feed(other, other.size))
        assertEquals(false, hits.started)
        // What a login's rc file printed before the sentinel does not count against it.
        val chatty = Hits("/r", cap = 10)
        val bytes = "hello\n$SEARCH_SENTINEL\u0000/r/a/x\u0000".toByteArray()
        assertEquals(listOf(Found("/r/a/x", "a/x")), chatty.feed(bytes, bytes.size))
        assertEquals(true, chatty.started)
    }

    @Test
    fun hitsLeaveOutTheFolderItselfAndItsOwnEntriesAndStopAtTheCap() {
        val hits = Hits("/r", cap = 2)
        val bytes = "$SEARCH_SENTINEL\u0000/r\u0000/r/top\u0000/r/a/x\u0000/r/b/y\u0000/r/c/z\u0000".toByteArray()
        assertEquals(listOf(Found("/r/a/x", "a/x"), Found("/r/b/y", "b/y")), hits.feed(bytes, bytes.size))
        assertTrue(hits.full)
    }

    @Test
    fun outcomeTellsUnreadablePlacesFromFailures() {
        assertEquals(SearchOutcome(SearchEnd.DONE), searchOutcome(0, emptyList(), 0))
        assertEquals(SearchOutcome(SearchEnd.NO_FIND), searchOutcome(127, listOf("sh: find: not found"), 1))
        assertEquals(SearchOutcome(SearchEnd.NO_ROOT), searchOutcome(NO_ROOT_STATUS, emptyList(), 0))
        // Places it could not read, whatever path they name.
        val denied = listOf("find: '/elsewhere/a': Permission denied", "find: '/r/b': No such file or directory")
        assertEquals(SearchOutcome(SearchEnd.DONE, skipped = 7), searchOutcome(1, denied, 7))
        // A path may hold a usage word: still a place it could not read (GNU, busybox, bfs quoting).
        val wordy = listOf(
            "find: '/r/invalid/z': Permission denied",
            "find: ‘/r/bad dir’: Permission denied",
            "find: /r/usage: x/unrecognized: Permission denied",
            "bfs: error: /r/invalid: Permission denied.",
        )
        assertEquals(SearchOutcome(SearchEnd.DONE, skipped = 4), searchOutcome(1, wordy, 4))
        listOf(
            "find: unknown predicate `-iname'",
            "find: unrecognized: -xdev",
            "find: paths must precede expression: `x'",
            "BusyBox v1.36.1 multi-call binary. Usage: find [-HL] [PATH]...",
        ).forEach { line -> assertEquals(line, SearchOutcome(SearchEnd.FAILED, message = line), searchOutcome(1, listOf(line), 1)) }
        assertEquals(SearchEnd.FAILED, searchOutcome(1, emptyList(), 0).end)
        assertEquals(SearchEnd.FAILED, searchOutcome(null, emptyList(), 0).end)
    }

    @Test
    fun findsNamesLiterallyWithoutRunningThemOrFollowingLinks() {
        val tricky = listOf("it's here", "a*b", "a?b", "[x]", "back\\slash", "\$(touch PWNED)", "semi;colon `x`")
        tricky.forEach { touch("sub/$it") }
        touch("sub/plain")
        touch("Sub2/README.md")
        touch("other/target-match")
        Files.createSymbolicLink(File(dir, "sub/linkdir").toPath(), File(dir, "other").toPath())
        touch("top-target")

        tricky.forEach { name -> assertEquals(name, listOf("sub/$name"), search(name)) }
        assertEquals(listOf("sub/a*b"), search("*"))
        assertEquals(listOf("sub/a?b"), search("?"))
        assertEquals(listOf("sub/back\\slash"), search("\\"))
        assertEquals(listOf("Sub2/README.md"), search("readme"))
        // Not through the link, and not the folder's own entry.
        assertEquals(listOf("other/target-match"), search("target"))
        assertFalse(File(dir, "PWNED").exists() || File(File(System.getProperty("user.dir")), "PWNED").exists())
    }

    @Test
    fun hiddenNamesOnlyWhenShown() {
        touch(".hidden/match-inside")
        touch("sub/.match-dot")
        touch("sub/match-plain")
        assertEquals(listOf("sub/match-plain"), search("match", hidden = false))
        assertEquals(listOf(".hidden/match-inside", "sub/.match-dot", "sub/match-plain"), search("match", hidden = true))
    }

    @Test
    fun anUnreadableFolderNamedLikeAUsageErrorIsSkipped() {
        touch("sub/invalid/hit-inside")
        touch("sub/hit-outside")
        val locked = File(dir, "sub/invalid")
        locked.setReadable(false, false)
        locked.setExecutable(false, false)
        try {
            // As root every folder reads: nothing to see there.
            Assume.assumeFalse(locked.listFiles() != null)
            val got = mutableListOf<Found>()
            val outcome = runBlocking { searchNames(::LocalCommand, dir.path, "hit", hidden = false) { got += it } }
            assertEquals(SearchOutcome(SearchEnd.DONE, skipped = 1), outcome)
            assertEquals(listOf("sub/hit-outside"), got.map { it.relative })
        } finally {
            locked.setReadable(true, false)
            locked.setExecutable(true, false)
        }
    }

    @Test
    fun aGoneFolderSaysSo() {
        val outcome = runBlocking { searchNames(::LocalCommand, File(dir, "gone").path, "x", hidden = false) {} }
        assertEquals(SearchEnd.NO_ROOT, outcome.end)
    }

    @Test
    fun aLoginThatRunsNoCommandsIsToldQuickly() {
        // SFTP only: nothing ever comes back (no answer). Nologin: a message, no sentinel, exit 1 (refused).
        mapOf(
            "exec sleep 30" to SearchEnd.NO_ANSWER,
            "echo 'This account is currently not available.'; exit 1" to SearchEnd.NO_COMMANDS,
        ).forEach { (shell, expected) ->
            val started = System.nanoTime()
            val outcome = runBlocking { searchNames({ LocalCommand(shell) }, dir.path, "x", hidden = false, startMs = 1_000) {} }
            assertEquals(shell, expected, outcome.end)
            assertTrue(shell, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 8)
        }
    }

    @Test
    fun stopsAtTheCap() {
        repeat(30) { touch("sub/hit-$it") }
        val got = mutableListOf<Found>()
        val outcome = runBlocking { searchNames(::LocalCommand, dir.path, "hit", hidden = false, cap = 10) { got += it } }
        assertEquals(SearchEnd.CAPPED, outcome.end)
        assertEquals(10, got.size)
    }

    @Test
    fun closingTheInputKillsTheFind() {
        // A find that would run for half a minute; only POSIX sh and sleep.
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "find").apply {
            writeText("#!/bin/sh\nexec sleep 30\n")
            setExecutable(true)
        }
        val command = LocalCommand(FIND_EXEC, path = bin.path)
        command.input.write(findScript(dir.path, "x", hidden = false).toByteArray())
        command.input.flush()
        // The sentinel: the script runs, find is started next.
        val sentinel = ByteArray(SEARCH_SENTINEL.length + 1)
        var got = 0
        while (got < sentinel.size) got += command.output.read(sentinel, got, sentinel.size - got).also { assertTrue(it > 0) }
        Thread.sleep(300)
        val started = System.nanoTime()
        command.closeInput()
        assertNotNull(command.exitStatus(10_000))
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
        command.close()
    }

    private fun search(query: String, hidden: Boolean = false): List<String> {
        val got = mutableListOf<Found>()
        val outcome = runBlocking { searchNames(::LocalCommand, dir.path, query, hidden) { got += it } }
        assertEquals(SearchEnd.DONE, outcome.end)
        return got.map { it.relative }.sorted()
    }

    private fun touch(relative: String) {
        val file = File(dir, relative)
        file.parentFile!!.mkdirs()
        file.writeText("")
    }

    /** [command] in a local sh, as the device's login shell would run it. */
    private class LocalCommand(command: String, path: String? = null) : RemoteCommand {
        private val process = ProcessBuilder("sh", "-c", command).apply {
            if (path != null) environment()["PATH"] = path + File.pathSeparator + environment()["PATH"]
        }.start()
        override val input = process.outputStream
        override val output = process.inputStream
        override val errors = process.errorStream

        override fun closeInput() = process.outputStream.close()

        override fun exitStatus(ms: Long): Int? = if (process.waitFor(ms, TimeUnit.MILLISECONDS)) process.exitValue() else null

        override fun close() {
            process.destroy()
        }
    }
}
