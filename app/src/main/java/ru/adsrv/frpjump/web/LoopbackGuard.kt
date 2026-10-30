package ru.adsrv.frpjump.web

/** Where a navigation in the in-app browser goes. */
enum class WebNavigation {
    /** This screen's tunnel, or a page-local scheme: stays in the WebView. */
    IN_APP,

    /** Anything off the phone (other hosts, mailto:, tel:, …): handed to other apps. */
    EXTERNAL,

    /** Another port on this phone's loopback (another tunnel, another app's server): refused. */
    BLOCKED,
}

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
private val PAGE_LOCAL_SCHEMES = setOf("about", "blob", "data", "javascript")
private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

/**
 * The in-app WebView shows one tunnel, `127.0.0.1:<tunnelPort>`: cleartext
 * is allowed to loopback alone, and a page of the owner's device must not
 * turn the app into a general browser nor reach other servers on the
 * phone's loopback (other tunnels, other apps). Anything that does not
 * parse goes external.
 */
fun webNavigationFor(url: String, tunnelPort: Int): WebNavigation {
    val scheme = SCHEME.find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return WebNavigation.EXTERNAL
    if (scheme in PAGE_LOCAL_SCHEMES) return WebNavigation.IN_APP
    val port = loopbackHttpPort(url) ?: return WebNavigation.EXTERNAL
    return if (port == tunnelPort) WebNavigation.IN_APP else WebNavigation.BLOCKED
}

/** True for an http(s) URL on this tunnel: what the WebView may load and download. */
fun isTunnelUrl(url: String, tunnelPort: Int): Boolean = loopbackHttpPort(url) == tunnelPort

/** True for an http(s) URL on any loopback port, which the WebView must not load unless it is the tunnel. */
fun isLoopbackHttpUrl(url: String): Boolean = loopbackHttpPort(url) != null

/**
 * The port of an http(s) URL whose host is this phone's loopback (80 or 443
 * when none is given), or null for anything else. Parsed the way browsers
 * split an authority: it ends at `/ ? # \`, the host follows the last `@`,
 * the port follows the host, leading zeros allowed. A loopback URL whose
 * port does not parse gives 0, which never matches a tunnel.
 */
fun loopbackHttpPort(url: String): Int? {
    val text = url.trim()
    val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val rest = text.substring(scheme.length + 1)
    if (!rest.startsWith("//")) return null
    val authority = rest.substring(2).takeWhile { it !in "/?#\\" }
    val hostAndPort = authority.substringAfterLast('@')
    val host: String
    val portText: String?
    if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        if (close < 0) return null
        host = hostAndPort.substring(0, close + 1)
        val after = hostAndPort.substring(close + 1)
        portText = if (after.isEmpty()) null else if (after.startsWith(":")) after.substring(1) else INVALID
    } else {
        host = hostAndPort.substringBefore(':')
        portText = if (':' in hostAndPort) hostAndPort.substringAfter(':') else null
    }
    if (host.lowercase() !in LOOPBACK_HOSTS) return null
    if (portText.isNullOrEmpty()) return if (scheme == "https") 443 else 80
    if (!portText.all { it in '0'..'9' }) return 0
    val digits = portText.trimStart('0')
    if (digits.length > 5) return 0
    return digits.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
}

private const val INVALID = "x"
