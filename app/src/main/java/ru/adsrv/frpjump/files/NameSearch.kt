package ru.adsrv.frpjump.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.ssh.shellQuote
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/*
 * Search by name (files contract, "Search"): the folder shown is filtered
 * on the phone ([nameMatches]); its subfolders are searched on the device
 * by one `find` on an exec channel of the files' SSH connection
 * ([searchNames]). One command finds at disk speed on the device; walking
 * the tree over SFTP would cost a round trip through the tunnel per folder.
 */

/** Hits the search in subfolders shows at most; then it stops. */
const val SEARCH_CAP = 500

/** The longest the search in subfolders runs. */
const val SEARCH_TIMEOUT_MS = 2 * 60 * 1000L

/** Whether a name holds [query] (trimmed), ignoring case: the folder's filter. */
fun nameMatches(name: String, query: String): Boolean = name.contains(query.trim(), ignoreCase = true)

/** [text] taken literally by find's `-name`, `-iname`, `-path`: `* ? [ ] \` lose their meaning. */
fun globEscape(text: String): String = buildString {
    text.forEach { c ->
        if (c in GLOB_SPECIAL) append('\\')
        append(c)
    }
}

private const val GLOB_SPECIAL = "*?[]\\"

/** What the files' SSH connection runs for the search: `sh` reading [findScript] from its input. */
const val FIND_EXEC = "sh -s"

/** The first record the script writes: commands run here (a nologin shell or SFTP-only login never writes it). */
const val SEARCH_SENTINEL = "frpjump-search"

/** The script's exit status when the folder is gone. */
const val NO_ROOT_STATUS = 66

/**
 * The POSIX sh script (one line, fed to [FIND_EXEC] on its input) that
 * writes [SEARCH_SENTINEL], then lists, NUL-separated, what is below
 * [root] (a physical absolute path) on its file system (`-xdev`) whose
 * name holds [query], ignoring case; links are not followed, hidden names
 * (and what is in hidden folders) are left out unless [hidden].
 *
 * - Sent on the input, not as an argument, so the login shell (fish too)
 *   never parses it: only `sh` reads the quoting. The root and the
 *   pattern are single-quoted, the query glob-escaped inside the quotes.
 * - Messages in English (`LC_MESSAGES=C`) for [searchOutcome]; the
 *   device's character type kept (an `LC_ALL` becomes `LC_CTYPE`), so
 *   `-iname` folds what the device's locale folds: Latin always,
 *   Cyrillic only in a UTF-8 locale.
 * - `find` runs in the background under `nice` where there is one; a
 *   watcher on the input (kept as fd 3: a background job's own input is
 *   /dev/null) kills it when the input ends, so closing the channel (Stop,
 *   a timeout, a dropped connection) ends the `find` on the device. `read`
 *   is a builtin: killing the watcher leaves nothing behind.
 */
fun findScript(root: String, query: String, hidden: Boolean): String {
    val at = shellQuote(root)
    val skipHidden = if (hidden) "" else "! -path ${shellQuote(globEscape(root))} -name '.*' -prune -o "
    val find = "\$n find $at -xdev $skipHidden-iname ${shellQuote("*" + globEscape(query.trim()) + "*")} -print0"
    return "printf '%s\\000' $SEARCH_SENTINEL; " +
        "c=\${LC_ALL:-\${LC_CTYPE:-\$LANG}}; unset LC_ALL; [ -n \"\$c\" ] && export LC_CTYPE=\"\$c\"; export LC_MESSAGES=C; " +
        "[ -d $at ] || exit $NO_ROOT_STATUS; " +
        "n=; command -v nice >/dev/null 2>&1 && n=nice; " +
        "exec 3<&0; $find & p=\$!; " +
        "(while read -r _; do :; done <&3; kill \$p) >/dev/null 2>&1 & w=\$!; " +
        "wait \$p; s=\$?; kill \$w 2>/dev/null; exit \$s\n"
}

/** One hit: its absolute [path] and that path below the searched folder. */
data class Found(val path: String, val relative: String)

/** [path] below [root] (`a/b`), or null when it is [root] itself or outside it. */
fun relativeTo(root: String, path: String): String? {
    val prefix = if (root.endsWith("/")) root else "$root/"
    return if (path.length > prefix.length && path.startsWith(prefix)) path.substring(prefix.length) else null
}

/** Splits a byte stream into the NUL-terminated paths `-print0` writes, as they come. */
class NulRecords {
    private val current = ByteArrayOutputStream()
    private var overlong = false

    /** The paths completed by [bytes] (`0 until count`). */
    fun feed(bytes: ByteArray, count: Int): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until count) {
            val b = bytes[i]
            if (b == 0.toByte()) {
                if (!overlong) out += current.toString(Charsets.UTF_8.name())
                current.reset()
                overlong = false
            } else if (current.size() < MAX_PATH_BYTES) {
                current.write(b.toInt())
            } else {
                overlong = true
            }
        }
        return out
    }

    private companion object {
        /** Well past PATH_MAX; a longer record is no path and is dropped. */
        const val MAX_PATH_BYTES = 64 * 1024
    }
}

/**
 * The hits in subfolders, from the script's records: first
 * [SEARCH_SENTINEL] ([started]), then `find`'s paths, the searched folder
 * and its own entries (the filter above shows those) left out, up to [cap].
 */
class Hits(private val root: String, private val cap: Int) {
    private val records = NulRecords()
    var count = 0
        private set
    val full: Boolean get() = count >= cap

    /** Null until the first record; then whether it was the sentinel (what a login shell printed before it is let go). */
    var started: Boolean? = null
        private set

    /** The new hits in [bytes], none past the cap; none at all when the first record was not the sentinel. */
    fun feed(bytes: ByteArray, size: Int): List<Found> {
        val out = mutableListOf<Found>()
        for (path in records.feed(bytes, size)) {
            if (started == null) {
                started = path.endsWith(SEARCH_SENTINEL)
                continue
            }
            if (started != true || full) break
            val relative = relativeTo(root, path) ?: continue
            if ('/' !in relative) continue
            out += Found(path, relative)
            count++
        }
        return out
    }
}

/** A command running on the device. */
interface RemoteCommand : Closeable {
    val input: OutputStream
    val output: InputStream
    val errors: InputStream

    /** Ends its standard input; [findScript] then kills its `find`. */
    fun closeInput()

    /** Its exit status, waiting up to [ms] for it; null when none came. */
    fun exitStatus(ms: Long): Int?
}

/** The device refused a command channel (an SFTP-only account); the connection is still up. */
class CommandsRefused(cause: Throwable) : IOException(cause.message, cause)

/**
 * How a search in subfolders ended: [NO_COMMANDS] the login may not run
 * commands (no sentinel came), [NO_ROOT] the folder is gone.
 */
enum class SearchEnd { DONE, CAPPED, TIMED_OUT, FAILED, NO_FIND, NO_COMMANDS, NO_ROOT }

/** [skipped]: places it could not read; [message]: what `find` said when it [SearchEnd.FAILED]. */
data class SearchOutcome(val end: SearchEnd, val skipped: Int = 0, val message: String? = null)

/** Words of `find`'s (English) messages about how it was called, not about a place. */
private val USAGE_MARKERS = listOf("unknown predicate", "unrecognized", "invalid", "paths must precede", "bad ", "unknown option", "missing argument", "usage:")

/**
 * `find`'s end: its exit [status] and the first [errors] it wrote
 * ([lines] in all). A message about how it was called is a failure; the
 * others name places it could not read (no permission, gone meanwhile),
 * counted as skipped; with no message, a non-zero status is a failure.
 */
fun searchOutcome(status: Int?, errors: List<String>, lines: Int): SearchOutcome {
    val usage = errors.firstOrNull { line -> USAGE_MARKERS.any { line.contains(it, ignoreCase = true) } }
    return when {
        status == 0 -> SearchOutcome(SearchEnd.DONE)
        status == 127 -> SearchOutcome(SearchEnd.NO_FIND)
        status == NO_ROOT_STATUS -> SearchOutcome(SearchEnd.NO_ROOT)
        usage != null -> SearchOutcome(SearchEnd.FAILED, message = usage)
        status == 1 && lines > 0 -> SearchOutcome(SearchEnd.DONE, skipped = lines)
        else -> SearchOutcome(SearchEnd.FAILED, message = errors.firstOrNull())
    }
}

/**
 * Runs [findScript] on [FIND_EXEC] through [start] and hands the hits to
 * [onFound] as they come (on an IO thread). No sentinel within
 * [startMs]: the login runs no commands. Ends at [cap] hits or after
 * [timeoutMs]; cancelled, or ended early, it closes the command, which
 * ends the `find`. Throws what [start] or the connection throws.
 */
suspend fun searchNames(
    start: (String) -> RemoteCommand,
    root: String,
    query: String,
    hidden: Boolean,
    cap: Int = SEARCH_CAP,
    timeoutMs: Long = SEARCH_TIMEOUT_MS,
    startMs: Long = SEARCH_START_MS,
    onFound: (List<Found>) -> Unit,
): SearchOutcome {
    val command = withContext(Dispatchers.IO) { start(FIND_EXEC) }
    return try {
        withContext(Dispatchers.IO) {
            try {
                command.input.write(findScript(root, query, hidden).toByteArray())
                command.input.flush()
            } catch (_: IOException) {
                // A login that ends at once (nologin) closes it: no sentinel comes, which says so.
            }
        }
        // The readers block until the streams end; not children, so a close stuck on a stalled
        // connection does not hold the search: they end when the channel does. [onFound] may
        // then still be called once after the search ended (early).
        val io = CoroutineScope(Dispatchers.IO)
        val errors = ErrorLines()
        val errorsRead = io.launch { errors.readFrom(command.errors) }
        val hits = Hits(root, cap)
        val started = CompletableDeferred<Boolean>()
        val reading = io.async {
            val buffer = ByteArray(8 * 1024)
            try {
                while (!hits.full) {
                    val n = command.output.read(buffer)
                    if (n < 0) break
                    val found = hits.feed(buffer, n)
                    hits.started?.let { started.complete(it) }
                    if (hits.started == false) break
                    if (found.isNotEmpty()) onFound(found)
                }
            } finally {
                started.complete(false)
            }
        }
        when {
            withTimeoutOrNull(startMs) { started.await() } != true -> SearchOutcome(SearchEnd.NO_COMMANDS)
            withTimeoutOrNull(timeoutMs) { reading.await() } == null -> SearchOutcome(SearchEnd.TIMED_OUT)
            hits.full -> SearchOutcome(SearchEnd.CAPPED)
            else -> {
                val status = withContext(Dispatchers.IO) { command.exitStatus(EXIT_WAIT_MS) }
                withTimeoutOrNull(EXIT_WAIT_MS) { errorsRead.join() }
                searchOutcome(status, errors.kept, errors.count)
            }
        }
    } finally {
        // Unblocks the readers and ends the find on the device, also when cancelled. Closing may
        // block on a stalled connection: it goes on by itself, the search does not wait for long.
        val closing = CoroutineScope(Dispatchers.IO).launch {
            command.closeInput()
            command.close()
        }
        withContext(NonCancellable) { withTimeoutOrNull(CLOSE_WAIT_MS) { closing.join() } }
    }
}

/** `find`'s stderr: how many lines, and the first few of them. */
private class ErrorLines {
    @Volatile
    var count = 0
        private set
    val kept: List<String> get() = synchronized(lines) { lines.toList() }
    private val lines = mutableListOf<String>()

    fun readFrom(input: InputStream) {
        try {
            input.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                count++
                synchronized(lines) { if (lines.size < KEPT) lines += line.take(MAX_LINE) }
            }
        } catch (_: IOException) {
            // Closed under it: the search ended.
        }
    }

    private companion object {
        const val KEPT = 5
        const val MAX_LINE = 300
    }
}

/** How long a login has to start the script ([SEARCH_SENTINEL]) before it counts as one that runs no commands. */
const val SEARCH_START_MS = 10_000L

private const val EXIT_WAIT_MS = 5_000L
private const val CLOSE_WAIT_MS = 3_000L
