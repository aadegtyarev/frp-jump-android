package ru.adsrv.frpjump.ui

import android.os.Handler
import android.os.Looper
import android.view.ActionMode

/**
 * The activity's open action modes. The floating selection toolbars of the terminal, a web page and
 * the text fields are system action modes, windows no Compose wrapper covers, so the lock ends them
 * here ([finishAll]) and one that starts while [locked] is ended at once. Main thread.
 */
class ActionModes(private val locked: () -> Boolean, private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }) {
    private val open = mutableListOf<ActionMode>()

    fun started(mode: ActionMode) {
        open += mode
        // Not inside the callback of its own start: on the next turn of the loop.
        if (locked()) post { mode.finish() }
    }

    fun finished(mode: ActionMode) {
        open -= mode
    }

    fun finishAll() {
        // finish() calls back into finished(), which edits the list.
        open.toList().forEach { it.finish() }
        open.clear()
    }
}
