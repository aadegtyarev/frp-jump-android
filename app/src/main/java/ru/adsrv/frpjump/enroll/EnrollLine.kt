package ru.adsrv.frpjump.enroll

import java.net.URI
import java.net.URISyntaxException

/** What the owner pasted: `[sudo] [frp-jump-client] [enroll] <url> <token> [--name X]`. */
data class EnrollLine(val controlUrl: String, val token: String, val requestedName: String?)

sealed interface EnrollLineResult {
    data class Ok(val line: EnrollLine) : EnrollLineResult
    data class Error(val problem: EnrollLineProblem) : EnrollLineResult
}

/** Why a pasted line is not an enroll line; never carries any of the token. */
enum class EnrollLineProblem {
    /** Nothing pasted. */
    EMPTY,
    /** `--name` with nothing after it. */
    NAME_WITHOUT_VALUE,
    /** Neither a URL nor a token. */
    NOTHING_AFTER_COMMAND,
    /** Too short for a URL and a token. */
    TOO_SHORT,
    /** The token is cut short or too long. */
    TOKEN_LENGTH,
    /** `http://` instead of `https://`. */
    HTTP,
    /** No usable `https://` URL before the token. */
    BAD_URL,
    /** The token has characters a token never has. */
    BAD_TOKEN,
}

/**
 * Reads the line `frp-jump-client devices add-token` prints, pasted as is.
 *
 * The CLI prints it with rich, which wraps a long line anywhere, inside
 * words too, so a paste is a list of fragments where any two neighbours may
 * have been one word. The parser leans on what is fixed instead of on the
 * spaces:
 *
 * - leading `sudo`, `frp-jump-client` (or a path ending in it) and `enroll`
 *   are dropped, also when split into fragments;
 * - `--name` / `--name=` starts the name: everything after it, glued;
 * - the token is `secrets.token_urlsafe(32)`, exactly [TOKEN_LENGTH]
 *   characters of `[A-Za-z0-9_-]`: the last ones of the remaining text, glued;
 *   whatever comes before it is the URL, which must end where a fragment
 *   ends (the space before the token survives any wrap). A token may itself start with `-`,
 *   so other `--` fragments are part of the token or the URL, not options.
 *
 * Errors name the problem only, never any of the token.
 */
object EnrollLineParser {
    /** `secrets.token_urlsafe(32)`: 32 bytes as unpadded base64url. */
    const val TOKEN_LENGTH = 43

    private val WHITESPACE = Regex("\\s+")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{$TOKEN_LENGTH}$")
    private const val CLIENT = "frp-jump-client"

    fun parse(text: String): EnrollLineResult {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return error(EnrollLineProblem.EMPTY)

        var i = 0
        i = skip(words, i) { it == "sudo" }
        i = skip(words, i) { (it == CLIENT || it.endsWith("/$CLIENT")) && "://" !in it }
        i = skip(words, i) { it == "enroll" }

        val rest = words.drop(i)
        val nameAt = rest.indexOfFirst { it == "--name" || it.startsWith("--name=") }
        var name: String? = null
        val body = if (nameAt < 0) {
            rest
        } else {
            name = (listOf(rest[nameAt].removePrefix("--name").removePrefix("=")) + rest.drop(nameAt + 1)).joinToString("")
            if (name.isEmpty()) return error(EnrollLineProblem.NAME_WITHOUT_VALUE)
            rest.take(nameAt)
        }

        val joined = body.joinToString("")
        if (joined.isEmpty()) return error(EnrollLineProblem.NOTHING_AFTER_COMMAND)
        if (joined.length <= TOKEN_LENGTH) return error(EnrollLineProblem.TOO_SHORT)
        val url = joined.dropLast(TOKEN_LENGTH)
        val token = joined.takeLast(TOKEN_LENGTH)
        // The space between URL and token survives any wrap, so the URL is made
        // of whole fragments; if not, the token was cut short (or is too long).
        if (body.runningFold(0) { at, word -> at + word.length }.none { it == url.length }) {
            return error(EnrollLineProblem.TOKEN_LENGTH)
        }
        if (url.startsWith("http://", ignoreCase = true)) {
            return error(EnrollLineProblem.HTTP)
        }
        val controlUrl = validateControlUrl(url)
            ?: return error(EnrollLineProblem.BAD_URL)
        if (!TOKEN.matches(token)) return error(EnrollLineProblem.BAD_TOKEN)

        return EnrollLineResult.Ok(EnrollLine(controlUrl, token, name?.takeUnless(::isPlaceholder)))
    }

    /**
     * Skips the word at [from] when it, or it glued to the next few fragments,
     * satisfies [matches]; returns the index after it, or [from] unchanged.
     */
    private fun skip(words: List<String>, from: Int, matches: (String) -> Boolean): Int {
        var joined = ""
        for (end in from until minOf(words.size, from + MAX_FRAGMENTS)) {
            joined += words[end]
            if (matches(joined)) return end + 1
        }
        return from
    }

    private const val MAX_FRAGMENTS = 4

    /** `--name <pick-a-name>` printed by the CLI when the token has no fixed name. */
    private fun isPlaceholder(name: String) = name.startsWith("<")

    private fun error(problem: EnrollLineProblem) = EnrollLineResult.Error(problem)
}

/**
 * The same rules as the server's `require_https_url`: https only, a host, no
 * credentials, query or fragment, no port 0. Returns the URL without a trailing
 * slash, or null when it cannot safely carry device credentials.
 */
fun validateControlUrl(value: String): String? {
    if (value.any { it.isWhitespace() || it.code < 32 || it.code == 127 } || '\\' in value) return null
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!"https".equals(uri.scheme, ignoreCase = true)) return null
    if (uri.host.isNullOrEmpty()) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.port == 0) return null
    return value.trimEnd('/')
}
