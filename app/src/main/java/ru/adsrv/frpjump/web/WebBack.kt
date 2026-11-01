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
 * to it lands right where Back started, forever. A bounce is a push, after
 * the Back landed, of the very URL Back left: the entry behind was a
 * redirect, and Back steps over it ([Bounce.SKIP_REDIRECT], two entries
 * back) or, with nothing before it, closes ([Bounce.CLOSE]). A push of
 * another URL (a link, the page's own navigation) is never a bounce and
 * disarms it, as does anything the owner starts ([cancel]); [capMs] after
 * the landing nothing counts any more.
 */
class BackBounce(private val capMs: Long = 2000) {
    private var fromIndex = -1
    private var fromUrl: String? = null
    private var landedAt = -1L

    /** Waiting for a Back to land or bounce: only then do history events need the back/forward list. */
    val armed: Boolean get() = fromUrl != null

    /** Back is about to leave the history entry at [index], showing [url]. A later Back replaces an earlier one. */
    fun leaving(index: Int, url: String) {
        fromIndex = index
        fromUrl = url
        landedAt = -1L
    }

    /** Forward, reload, another page asked for: whatever comes next is not a bounce. */
    fun cancel() {
        fromIndex = -1
        fromUrl = null
        landedAt = -1L
    }

    /** A history entry at [index] of the web view's back/forward list now shows [url]; non-null when Back bounced. */
    fun landed(index: Int, url: String, now: Long): Bounce? {
        val left = fromUrl ?: return null
        if (landedAt >= 0 && now - landedAt > capMs) {
            cancel()
            return null
        }
        if (index < fromIndex) {
            // The Back's landing (or an in-page route on it): the cap runs from the first one.
            if (landedAt < 0) landedAt = now
            return null
        }
        cancel()
        if (url != left) return null
        // The list is now [... earlier, redirect, pushed]: earlier is two back, if there is one.
        return if (index >= 2) Bounce.SKIP_REDIRECT else Bounce.CLOSE
    }
}
