package ru.adsrv.frpjump.ui.ssh

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.ssh.CloseAllPlan
import ru.adsrv.frpjump.ssh.CloseAllTab
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
 * goes are the tabs closed and the sessions found ended on the device;
 * Undo brings the tabs back as they were, nothing ended. When the device
 * cannot be asked, the tabs still close the same way and the notice says
 * the sessions there stay. Main thread; suspends while the device answers.
 */
suspend fun closeAllOfPhone(context: Context, sessions: SshSessions, pendingUndo: PendingUndo, from: SshSession) {
    val device = from.device
    context.notice(context.getString(R.string.terminal_close_all_looking, device), NoticeKind.INFO, key = LOOKING_KEY)
    val listing = try {
        Result.success(withContext(Dispatchers.IO) { sessions.ownTmuxOn(from) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
    val hidden = pendingUndo.hidden.value
    val tabs = sessions.sessions.value.map {
        CloseAllTab(
            id = it.id,
            name = it.tmux.assigned,
            here = it.profile == from.profile && it.device == from.device && it.port == from.port,
            hidden = sessionHidden(hidden, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)),
        )
    }
    val plan = closeAllPlan(tabs, listing.getOrDefault(emptyList()))
    val failure = listing.exceptionOrNull()
    when {
        // The tabs still close (below); what runs on the device stays, and the notice says so.
        failure != null -> {
            val text = if (plan.tabs.isEmpty()) R.string.terminal_close_all_failed else R.string.terminal_close_all_failed_tabs
            context.notice(context.getString(text, device, failure.userMessage()), NoticeKind.WARN, long = true, key = LOOKING_KEY)
        }
        plan.count == 0 -> {
            context.notice(context.getString(R.string.terminal_close_all_none, device), NoticeKind.INFO, key = LOOKING_KEY)
            return
        }
        // The Undo notice takes the place of "Looking for…".
        else -> context.notices.dismissKey(LOOKING_KEY)
    }
    val app = context.applicationContext
    scheduleCloseAll(
        pendingUndo, plan,
        orphanKey = { PendingUndo.tmuxKey(from.profile, from.device, from.port, from.user, it) },
        closeTab = { sessions.close(it, endTmux = true) },
        endOnDevice = { names ->
            val failure = withContext(Dispatchers.IO) { runCatching { sessions.endTmuxOn(from, names) }.exceptionOrNull() }
            if (failure != null && failure !is CancellationException) {
                app.notice(app.getString(R.string.terminal_close_all_end_failed, device, failure.userMessage()), NoticeKind.WARN, long = true)
            }
        },
    )
}

/**
 * Puts [plan] behind the Undo notice: one action per tab ([closeTab] when
 * it goes) and per session without a tab, each counted as closed. Keys
 * already waiting (a second "Close all" while the first one's notice is up)
 * are not added again, and the sessions are ended ([endOnDevice], one call)
 * with the last action actually added, after the tabs: so every batch that
 * adds anything ends its sessions exactly once, and Undo ends none.
 * Returns how many actions were added. Main thread.
 */
fun scheduleCloseAll(
    pendingUndo: PendingUndo,
    plan: CloseAllPlan,
    orphanKey: (String) -> String,
    closeTab: (Int) -> Unit,
    endOnDevice: suspend (List<String>) -> Unit,
): Int {
    val waiting = pendingUndo.hidden.value
    val actions: List<Pair<String, () -> Unit>> =
        plan.tabs.map { id -> PendingUndo.sessionKey(SessionKind.TERMINAL, id) to { closeTab(id) } } +
            plan.orphans.map { name -> orphanKey(name) to {} }
    val added = actions.filter { it.first !in waiting }
    added.forEachIndexed { i, (key, close) ->
        val last = i == added.lastIndex
        pendingUndo.enqueue(key, kind = UndoKind.CLOSED, commit = {
            close()
            if (last && plan.kill.isNotEmpty()) endOnDevice(plan.kill)
        })
    }
    return added.size
}
