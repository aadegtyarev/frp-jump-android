package ru.adsrv.frpjump.web

/**
 * The page each tunnel's web view showed last, in memory for this run of
 * the app: Back destroys the web view, and opening the same tunnel again
 * continues on that page rather than at `/`. Keyed by (profile, device,
 * local port), as [WebSiteMemory] is; only pages of that tunnel are kept.
 */
class WebPageMemory {
    private val pages = mutableMapOf<Triple<String, String, Int>, String>()

    /** Keeps [url] as the last page of [device]:[localPort] of [profile]; anything off that tunnel (an error page, another host) is ignored. */
    @Synchronized
    fun remember(profile: String, device: String, localPort: Int, url: String) {
        if (isTunnelUrl(url, localPort)) pages[Triple(profile, device, localPort)] = url
    }

    /**
     * Where the web view of [device]:[localPort] of [profile] starts: the page
     * shown there last, when it is on [start]'s origin (the same tunnel, the
     * same scheme: a switch to HTTP starts over), else [start].
     */
    @Synchronized
    fun startFor(profile: String, device: String, localPort: Int, start: String): String {
        val last = pages[Triple(profile, device, localPort)] ?: return start
        return last.takeIf { isTunnelUrl(it, localPort) && it.startsWith(start.trimEnd('/') + "/") } ?: start
    }

    /** The pages of [profile] go with it ("Forget profile"). */
    @Synchronized
    fun forgetProfile(profile: String) {
        pages.keys.removeAll { it.first == profile }
    }
}
