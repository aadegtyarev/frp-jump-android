package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.ssh.TapAnswer

/** What a tap on the command line under tmux does once tmux has answered. */
enum class TapAction {
    /** Send the arrows: the shell's cursor moves. */
    ARROWS,

    /** Hand the program the click the view would have sent. */
    CLICK,

    /** Nothing. */
    NOTHING,
}

/**
 * [answer] for a tap whose screen is [unchanged] since the tap: arrows only
 * for a shell on the very screen that was tapped (output, typing or a moved
 * cursor in between would land them elsewhere); a click for anything else
 * that answered in time; nothing for no answer, so a click never comes late.
 */
fun tapAction(answer: TapAnswer, unchanged: Boolean): TapAction = when (answer) {
    TapAnswer.SHELL -> if (unchanged) TapAction.ARROWS else TapAction.NOTHING
    TapAnswer.NOT_SHELL -> TapAction.CLICK
    TapAnswer.NONE -> TapAction.NOTHING
}

/**
 * Taps on the command line under tmux: one question to tmux at a time, and
 * its answer kept for [CACHE_MS] for the same session on the same screen.
 * Main thread: [tap]'s `ask` must call back there.
 *
 * The screen is the caller's [cursorLineState]: the cursor's line and
 * place in it. Echoed typing, output on that line or a moved cursor
 * changes it, so a changed screen is a new question, and the answer for an
 * old one is used only to click.
 */
class TmuxTapGate(private val clock: () -> Long) {
    private var question = 0L
    private var pendingSince: Long? = null
    private var cached: Cached? = null

    private class Cached(val session: Any, val screen: String, val at: Long, val answer: TapAnswer)

    /**
     * A tap on [session] showing [screen]. [ask] asks tmux and calls back
     * once; [screenNow] is the fingerprint when the answer comes; [act] does
     * the [TapAction]. False: a question is still out and this tap is
     * dropped.
     */
    fun tap(
        session: Any,
        screen: String,
        ask: ((TapAnswer) -> Unit) -> Unit,
        screenNow: () -> String,
        act: (TapAction) -> Unit,
    ): Boolean {
        val now = clock()
        val since = pendingSince
        if (since != null && now - since < PENDING_MAX_MS) return false
        cached?.let {
            if (it.session === session && it.screen == screen && now - it.at <= CACHE_MS) {
                act(tapAction(it.answer, unchanged = true))
                return true
            }
        }
        val id = ++question
        pendingSince = now
        ask { answer ->
            if (id == question) {
                pendingSince = null
                val unchanged = screenNow() == screen
                cached = if (answer != TapAnswer.NONE && unchanged) Cached(session, screen, now, answer) else null
                act(tapAction(answer, unchanged))
            }
        }
        return true
    }

    companion object {
        /** How long an answer stands for an unchanged screen. */
        const val CACHE_MS = 400L

        /** A question older than this no longer holds taps back (an exec gives up after 10 s). */
        const val PENDING_MAX_MS = 12_000L
    }
}
