package ru.adsrv.frpjump.web

/** What Back does on the web screen. */
enum class WebBack { LEAVE_FULLSCREEN, PAGE_BACK, CLOSE }

/**
 * Back on the web screen, system or the bar's arrow: full screen is left
 * first, then the page's history is walked (in-page routes too), then the
 * screen closes. A hidden page (tunnel gone, renderer crashed) is not
 * walked: Back leaves.
 */
fun webBackFor(fullscreen: Boolean, canGoBack: Boolean, pageShown: Boolean): WebBack = when {
    fullscreen -> WebBack.LEAVE_FULLSCREEN
    canGoBack && pageShown -> WebBack.PAGE_BACK
    else -> WebBack.CLOSE
}

/** What to do once a Back bounced off a redirecting entry. */
enum class Bounce { SKIP_REDIRECT, CLOSE }

/**
 * Catches a Back that bounces: single-page UIs (homeui) keep a first entry
 * (`/`) that pushes their start route the moment it is shown, so going back
 * to it lands right where Back started, forever. When, shortly after a
 * Back, the page pushes an entry at or past the one Back left, that entry
 * behind was a redirect: Back then steps over it ([Bounce.SKIP_REDIRECT],
 * two entries back) or, with nothing before it, closes ([Bounce.CLOSE]).
 */
class BackBounce(private val windowMs: Long = 1000) {
    private var fromIndex = -1
    private var at = 0L

    /** Back is about to leave the history entry at [index]. */
    fun leaving(index: Int, now: Long) {
        fromIndex = index
        at = now
    }

    /** A history entry is now at [index] (of the web view's back/forward list); non-null when Back bounced. */
    fun landed(index: Int, now: Long): Bounce? {
        if (fromIndex < 0) return null
        if (now - at > windowMs) {
            fromIndex = -1
            return null
        }
        // The back landing itself (or an in-page route on it): wait for a push.
        if (index < fromIndex) return null
        fromIndex = -1
        // The list is now [... earlier, redirect, pushed]: earlier is two back, if there is one.
        return if (index >= 2) Bounce.SKIP_REDIRECT else Bounce.CLOSE
    }
}
