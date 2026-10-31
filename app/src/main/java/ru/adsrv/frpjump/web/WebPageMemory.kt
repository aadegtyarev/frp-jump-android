package ru.adsrv.frpjump.web

/**
 * The page each tunnel's web view showed last, in memory for this run of
 * the app: Back destroys the web view, and opening the same tunnel again
 * reloads that page rather than `/`, as a browser restores a tab. Keyed
 * by (profile, device, target port, local port): the local port alone
 * may be given to another service's tunnel after a rebind. Only pages of
 * that tunnel are kept.
 */
class WebPageMemory {
    private data class Key(val profile: String, val device: String, val targetPort: Int, val localPort: Int)

    private val pages = mutableMapOf<Key, String>()

    /** Keeps [url] as the last page of this tunnel; anything off it (an error page, another host or port) is ignored. */
    @Synchronized
    fun remember(profile: String, device: String, targetPort: Int, localPort: Int, url: String) {
        if (isTunnelUrl(url, localPort)) pages[Key(profile, device, targetPort, localPort)] = url
    }

    /**
     * Where this tunnel's web view starts: the page shown there last, when
     * it is on [start]'s origin (the same tunnel, the same scheme: a switch
     * to HTTP starts over), else [start].
     */
    @Synchronized
    fun startFor(profile: String, device: String, targetPort: Int, localPort: Int, start: String): String {
        val last = pages[Key(profile, device, targetPort, localPort)] ?: return start
        return last.takeIf { isTunnelUrl(it, localPort) && it.startsWith(start.trimEnd('/') + "/") } ?: start
    }

    /** The pages of [profile] go with it ("Forget profile", with or without its data). */
    @Synchronized
    fun forgetProfile(profile: String) {
        pages.keys.removeAll { it.profile == profile }
    }
}

/**
 * Which of a web view's pages are worth remembering: one that finished
 * loading without a main-frame error, and the in-page moves after it
 * (hash and history routes, which load nothing). A broken address, or
 * WebView's error page for it, is never kept. Main thread.
 */
class GoodPages {
    private var failed = false
    private var loaded = false

    /** A new main-frame load began. */
    fun started() {
        failed = false
        loaded = false
    }

    /** The main frame failed to load. */
    fun failed() {
        failed = true
    }

    /** The load finished: [url] to remember, or null. */
    fun finished(url: String): String? {
        loaded = !failed
        return url.takeIf { loaded }
    }

    /** A history entry (also an in-page route): [url] to remember once the page it is on loaded well, else null. */
    fun history(url: String): String? = url.takeIf { loaded }
}
