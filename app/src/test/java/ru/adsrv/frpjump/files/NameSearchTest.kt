package ru.adsrv.frpjump.files

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun findCommandRunsInShWithQuotedWords() {
        val command = findCommand("/mnt/my data", "it's", hidden = true)
        assertTrue(command, command.startsWith("sh -c '"))
        // Inside the sh -c word, the root and the pattern are single-quoted words of their own.
        assertTrue(command, command.contains("find '\\''/mnt/my data'\\''"))
        assertTrue(command, command.contains("-iname '\\''*it'\\''\\'\\'''\\''s*'\\'' -print0"))
        assertFalse(command, command.contains("-prune"))
        assertTrue(findCommand("/x", "a", hidden = false).contains("-name '\\''.*'\\'' -prune -o"))
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
    fun hitsLeaveOutTheFolderItselfAndItsOwnEntriesAndStopAtTheCap() {
        val hits = Hits("/r", cap = 2)
        val bytes = "/r\u0000/r/top\u0000/r/a/x\u0000/r/b/y\u0000/r/c/z\u0000".toByteArray()
        assertEquals(listOf(Found("/r/a/x", "a/x"), Found("/r/b/y", "b/y")), hits.feed(bytes, bytes.size))
        assertTrue(hits.full)
    }

    @Test
    fun outcomeTellsUnreadableFoldersFromFailures() {
        assertEquals(SearchOutcome(SearchEnd.DONE), searchOutcome(0, emptyList(), 0, "/r"))
        assertEquals(SearchOutcome(SearchEnd.NO_FIND), searchOutcome(127, listOf("sh: find: not found"), 1, "/r"))
        val denied = listOf("find: ‘/r/a’: Permission denied", "find: '/r/b': Отказано в доступе")
        assertEquals(SearchOutcome(SearchEnd.DONE, skipped = 7), searchOutcome(1, denied, 7, "/r"))
        assertEquals(
            SearchOutcome(SearchEnd.FAILED, message = "find: unknown predicate `-iname'"),
            searchOutcome(1, listOf("find: unknown predicate `-iname'"), 1, "/r"),
        )
        assertEquals(SearchEnd.FAILED, searchOutcome(null, emptyList(), 0, "/r").end)
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
        val command = LocalCommand(findCommand(dir.path, "x", hidden = false), path = bin.path)
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
        override val output = process.inputStream
        override val errors = process.errorStream

        override fun closeInput() = process.outputStream.close()

        override fun exitStatus(ms: Long): Int? = if (process.waitFor(ms, TimeUnit.MILLISECONDS)) process.exitValue() else null

        override fun close() {
            process.destroy()
        }
    }
}
