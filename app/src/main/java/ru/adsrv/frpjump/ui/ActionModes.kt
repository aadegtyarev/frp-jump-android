package ru.adsrv.frpjump.ui

import android.view.ActionMode

/**
 * The activity's open action modes. The floating selection toolbars of the terminal, a web page and
 * the text fields are system action modes, windows no Compose wrapper covers, so the lock ends them
 * here ([finishAll]) and one that starts while [locked] is ended at once. Main thread.
 */
class ActionModes(private val locked: () -> Boolean) {
    private val open = mutableListOf<ActionMode>()

    fun started(mode: ActionMode) {
        open += mode
        if (locked()) mode.finish()
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
