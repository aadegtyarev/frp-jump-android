package ru.adsrv.frpjump.ui.ssh

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.closeAllPlan
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.notices
import ru.adsrv.frpjump.ui.userMessage
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.UndoKind

/** The notice "Looking for…" while the device is asked, replaced by what follows. */
private const val LOOKING_KEY = "close-all-looking"

/**
 * "Close all sessions of this phone on <device>" from the terminal's "⋮",
 * for [from]'s device, port and user. Undo instead of confirm
 * (docs/contract.md, UI rules): the device is asked which of this
 * install's tmux sessions run there (read only); then the tabs to that
 * device and port leave the screen at once and the Undo notice counts
 * them with the sessions no tab holds ("Closed: N · Undo"). Only when it
 * goes are the tabs closed and every session found ended on the device;
 * Undo brings the tabs back as they were, nothing ended. Main thread;
 * suspends while the device answers.
 */
suspend fun closeAllOfPhone(context: Context, sessions: SshSessions, pendingUndo: PendingUndo, from: SshSession) {
    val device = from.device
    context.notice(context.getString(R.string.terminal_close_all_looking, device), NoticeKind.INFO, key = LOOKING_KEY)
    val found = try {
        withContext(Dispatchers.IO) { sessions.ownTmuxOn(from) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        context.notice(context.getString(R.string.terminal_close_all_failed, device, e.userMessage()), NoticeKind.WARN, long = true, key = LOOKING_KEY)
        return
    }
    val hidden = pendingUndo.hidden.value
    val all = sessions.sessions.value
    val shown = all.filter { it.profile == from.profile && it.device == from.device && it.port == from.port }
        .filterNot { sessionHidden(hidden, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)) }
    val plan = closeAllPlan(shown.map { it.id }, all.mapNotNull { it.tmux.assigned }.toSet(), found)
    if (plan.count == 0) {
        context.notice(context.getString(R.string.terminal_close_all_none, device), NoticeKind.INFO, key = LOOKING_KEY)
        return
    }
    // The Undo notice takes the place of "Looking for…".
    context.notices.dismissKey(LOOKING_KEY)
    val app = context.applicationContext
    val keys = plan.tabs.map { PendingUndo.sessionKey(SessionKind.TERMINAL, it) } +
        plan.orphans.map { PendingUndo.tmuxKey(from.profile, from.device, from.port, from.user, it) }
    val last = keys.last()
    // The device's sessions go after the tabs, in one exec, with the last commit of the batch.
    suspend fun endFound() {
        if (plan.kill.isEmpty()) return
        val failure = withContext(Dispatchers.IO) { runCatching { sessions.endTmuxOn(from, plan.kill) }.exceptionOrNull() }
        if (failure != null && failure !is CancellationException) {
            app.notice(app.getString(R.string.terminal_close_all_end_failed, device, failure.userMessage()), NoticeKind.WARN, long = true)
        }
    }
    plan.tabs.forEachIndexed { i, id ->
        pendingUndo.enqueue(keys[i], kind = UndoKind.CLOSED, commit = {
            // Its own session ends with it when its connection is up; the rest below.
            sessions.close(id, endTmux = true)
            if (keys[i] == last) endFound()
        })
    }
    plan.orphans.forEachIndexed { i, _ ->
        val key = keys[plan.tabs.size + i]
        pendingUndo.enqueue(key, kind = UndoKind.CLOSED, commit = { if (key == last) endFound() })
    }
}
