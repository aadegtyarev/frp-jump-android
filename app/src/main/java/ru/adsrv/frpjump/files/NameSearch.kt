package ru.adsrv.frpjump.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.ssh.shellQuote
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

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

/**
 * The shell command that lists, NUL-separated, what is below [root] (a
 * physical absolute path) whose name holds [query], ignoring case; links
 * are not followed, hidden names (and what is in hidden folders) are left
 * out unless [hidden]. Every word the app chooses is single-quoted, the
 * query glob-escaped inside the quotes.
 *
 * `find` runs in the background, a watcher on the channel's input kills
 * it when that input ends: closing the channel (Stop, a timeout, a
 * dropped connection) ends the `find` on the device, not just the reading.
 * The input is kept as fd 3, since a background job's own input is
 * /dev/null; `read` is a builtin, so killing the watcher leaves nothing.
 * Unreadable folders go to stderr, which the app counts. It runs in `sh`
 * whatever the login shell is (the script is POSIX sh).
 */
fun findCommand(root: String, query: String, hidden: Boolean): String {
    val skipHidden = if (hidden) "" else "! -path ${shellQuote(globEscape(root))} -name '.*' -prune -o "
    val find = "find ${shellQuote(root)} $skipHidden-iname ${shellQuote("*" + globEscape(query.trim()) + "*")} -print0"
    val script = "exec 3<&0; $find & p=\$!; " +
        "(while read -r _; do :; done <&3; kill \$p) >/dev/null 2>&1 & w=\$!; " +
        "wait \$p; s=\$?; kill \$w 2>/dev/null; exit \$s"
    return "sh -c ${shellQuote(script)}"
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
 * The hits in subfolders, from `find`'s paths: the searched folder and its
 * own entries (the filter above shows those) left out, up to [cap].
 */
class Hits(private val root: String, private val cap: Int) {
    private val records = NulRecords()
    var count = 0
        private set
    val full: Boolean get() = count >= cap

    /** The new hits in [bytes], none past the cap. */
    fun feed(bytes: ByteArray, size: Int): List<Found> {
        val out = mutableListOf<Found>()
        for (path in records.feed(bytes, size)) {
            if (full) break
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
    val output: InputStream
    val errors: InputStream

    /** Ends its standard input; [findCommand] then kills its `find`. */
    fun closeInput()

    /** Its exit status, waiting up to [ms] for it; null when none came. */
    fun exitStatus(ms: Long): Int?
}

/** The device refused a command channel (an SFTP-only account); the connection is still up. */
class CommandsRefused(cause: Throwable) : IOException(cause.message, cause)

/** How a search in subfolders ended. */
enum class SearchEnd { DONE, CAPPED, TIMED_OUT, FAILED, NO_FIND }

/** [skipped]: folders it could not read; [message]: what `find` said when it [SearchEnd.FAILED]. */
data class SearchOutcome(val end: SearchEnd, val skipped: Int = 0, val message: String? = null)

/**
 * `find`'s end: its exit [status] and the [errors] it wrote ([lines] of
 * them in all). Messages naming a place below [root] are folders it could
 * not read, whatever the device's language; anything else is a failure.
 */
fun searchOutcome(status: Int?, errors: List<String>, lines: Int, root: String): SearchOutcome {
    val aboutPlaces = errors.all { root in it }
    return when {
        status == 0 -> SearchOutcome(SearchEnd.DONE)
        status == 127 -> SearchOutcome(SearchEnd.NO_FIND)
        status != null && lines > 0 && aboutPlaces -> SearchOutcome(SearchEnd.DONE, skipped = lines)
        else -> SearchOutcome(SearchEnd.FAILED, message = errors.firstOrNull { root !in it } ?: errors.firstOrNull())
    }
}

/**
 * Runs [findCommand] through [start] and hands the hits to [onFound] as
 * they come (on an IO thread). Ends at [cap] hits or after [timeoutMs];
 * cancelled, or ended early, it closes the command, which ends the `find`.
 * Throws what [start] or the connection throws.
 */
suspend fun searchNames(
    start: (String) -> RemoteCommand,
    root: String,
    query: String,
    hidden: Boolean,
    cap: Int = SEARCH_CAP,
    timeoutMs: Long = SEARCH_TIMEOUT_MS,
    onFound: (List<Found>) -> Unit,
): SearchOutcome = coroutineScope {
    val command = withContext(Dispatchers.IO) { start(findCommand(root, query, hidden)) }
    try {
        val errors = ErrorLines()
        val errorsRead = launch(Dispatchers.IO) { errors.readFrom(command.errors) }
        val hits = Hits(root, cap)
        val reading = async(Dispatchers.IO) {
            val buffer = ByteArray(8 * 1024)
            while (!hits.full) {
                val n = command.output.read(buffer)
                if (n < 0) break
                val found = hits.feed(buffer, n)
                if (found.isNotEmpty()) onFound(found)
            }
        }
        val finished = withTimeoutOrNull(timeoutMs) { reading.await() } != null
        when {
            !finished -> SearchOutcome(SearchEnd.TIMED_OUT)
            hits.full -> SearchOutcome(SearchEnd.CAPPED)
            else -> {
                val status = withContext(Dispatchers.IO) { command.exitStatus(EXIT_WAIT_MS) }
                withTimeoutOrNull(EXIT_WAIT_MS) { errorsRead.join() }
                searchOutcome(status, errors.kept, errors.count, root)
            }
        }
    } finally {
        // Unblocks the readers and ends the find on the device; quick, and done also when cancelled.
        withContext(NonCancellable + Dispatchers.IO) {
            command.closeInput()
            command.close()
        }
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

private const val EXIT_WAIT_MS = 5_000L
