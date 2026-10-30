package ru.adsrv.frpjump.web

/** Where a navigation in the in-app browser goes. */
enum class WebNavigation {
    /** A tunnel on this phone, or a page-local scheme: stays in the WebView. */
    IN_APP,

    /** Anything else (other hosts, mailto:, tel:, …): handed to other apps. */
    EXTERNAL,
}

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
private val PAGE_LOCAL_SCHEMES = setOf("about", "blob", "data", "javascript")
private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

/**
 * The in-app WebView only shows tunnels: cleartext is allowed to loopback
 * alone, and a page of the owner's device must not turn the app into a
 * general browser. Anything that does not parse goes external too.
 */
fun webNavigationFor(url: String): WebNavigation {
    val scheme = SCHEME.find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return WebNavigation.EXTERNAL
    if (scheme in PAGE_LOCAL_SCHEMES) return WebNavigation.IN_APP
    return if (isLoopbackHttpUrl(url)) WebNavigation.IN_APP else WebNavigation.EXTERNAL
}

/**
 * True for an http(s) URL whose host is this phone's loopback, which a tunnel
 * serves. Parsed the way browsers split an authority: it ends at `/ ? # \`,
 * the host follows the last `@`, the port follows the host.
 */
fun isLoopbackHttpUrl(url: String): Boolean {
    val text = url.trim()
    val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    val rest = text.substring(scheme.length + 1)
    if (!rest.startsWith("//")) return false
    val authority = rest.substring(2).takeWhile { it !in "/?#\\" }
    val hostAndPort = authority.substringAfterLast('@')
    val host = if (hostAndPort.startsWith("[")) {
        hostAndPort.substringBefore(']', missingDelimiterValue = "") + "]"
    } else {
        hostAndPort.substringBefore(':')
    }
    return host.lowercase() in LOOPBACK_HOSTS
}
