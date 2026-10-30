package ru.adsrv.frpjump.enroll

import java.net.URI
import java.net.URISyntaxException

/** What the owner pasted: `[sudo] [frp-jump-client] [enroll] <url> <token> [--name X]`. */
data class EnrollLine(val controlUrl: String, val token: String, val requestedName: String?)

sealed interface EnrollLineResult {
    data class Ok(val line: EnrollLine) : EnrollLineResult
    data class Error(val message: String) : EnrollLineResult
}

object EnrollLineParser {
    private val WHITESPACE = Regex("\\s+")
    private val TOKEN = Regex("^[A-Za-z0-9_-]+$")

    // A token is URL-safe base64, so a word with one of these is a piece of a URL
    // that the CLI's terminal wrapped mid-word.
    private val URL_ONLY_CHARS = charArrayOf('.', '/', ':')

    fun parse(text: String): EnrollLineResult {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return error("Paste the enroll line printed by frp-jump-client.")

        var i = 0
        if (words.getOrNull(i) == "sudo") i++
        if (words.getOrNull(i)?.let { it == "frp-jump-client" || it.endsWith("/frp-jump-client") } == true) i++
        if (words.getOrNull(i) == "enroll") i++

        val positional = mutableListOf<String>()
        var name: String? = null
        while (i < words.size) {
            val word = words[i]
            when {
                word == "--name" -> {
                    name = words.getOrNull(i + 1) ?: return error("--name needs a value.")
                    i++
                }
                word.startsWith("--name=") -> name = word.removePrefix("--name=")
                word.startsWith("-") && positional.isEmpty() -> return error("Unknown option $word.")
                word.startsWith("--") -> return error("Unknown option $word.")
                else -> positional += word
            }
            i++
        }

        if (positional.isEmpty()) return error("Expected the server URL and the token.")
        var url = positional.first()
        var rest = positional.drop(1)
        while (rest.isNotEmpty() && rest.first().any { it in URL_ONLY_CHARS }) {
            url += rest.first()
            rest = rest.drop(1)
        }
        if (url.startsWith("http://", ignoreCase = true)) {
            return error("The server URL must start with https://, not http://.")
        }
        val controlUrl = validateControlUrl(url)
            ?: return error("Expected an https:// server URL, got \"$url\".")
        if (rest.isEmpty()) return error("The token is missing after the server URL.")
        val token = rest.joinToString("")
        if (!TOKEN.matches(token)) return error("The token does not look right: \"$token\".")

        return EnrollLineResult.Ok(EnrollLine(controlUrl, token, name?.takeUnless(::isPlaceholder)))
    }

    /** `--name <pick-a-name>` printed by the CLI when the token has no fixed name. */
    private fun isPlaceholder(name: String) = name.isEmpty() || name.startsWith("<")

    private fun error(message: String) = EnrollLineResult.Error(message)
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
