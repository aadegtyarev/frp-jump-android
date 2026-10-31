package ru.adsrv.frpjump.sessions

import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.UndoKind

/**
 * Closing a session with Undo (docs/contract.md, UI rules): it leaves the
 * tabs, the Sessions tab and the counts at once, and [closeTerminal] or
 * [closeMqtt] ends it when the notice goes. Undo shows it again, still
 * running. Main thread.
 */
class SessionCloser(
    private val pendingUndo: PendingUndo,
    private val closeTerminal: (Int) -> Unit,
    private val closeMqtt: (Int) -> Unit,
) {
    /** Terminal session [id]; [undo] runs when it comes back (its screen selects it again). */
    fun terminal(id: Int, undo: () -> Unit = {}) =
        pendingUndo.enqueue(PendingUndo.sessionKey(SessionKind.TERMINAL, id), commit = { closeTerminal(id) }, undo = undo, kind = UndoKind.CLOSED)

    /** MQTT explorer session [id]. */
    fun mqtt(id: Int) =
        pendingUndo.enqueue(PendingUndo.sessionKey(SessionKind.MQTT, id), commit = { closeMqtt(id) }, kind = UndoKind.CLOSED)
}
