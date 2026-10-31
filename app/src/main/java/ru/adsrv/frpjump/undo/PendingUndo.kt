package ru.adsrv.frpjump.undo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * "Undo instead of confirm" (docs/contract.md, UI rules): a destructive
 * action is only hidden at once; it is done ([enqueue]'s commit) when the
 * notice with Undo goes, about [delayMs] after the last action. Several in
 * a row share one notice and one Undo, which brings them all back. An
 * action that is hidden, waiting or still being done, has its key in
 * [hidden]: the screens leave it out of their lists and counts, so it
 * cannot be opened or acted on. [commitAll] does everything waiting at
 * once: the app calls it when its screens stop (the process may then go
 * at any time) and before a profile is forgotten.
 *
 * Main thread, except that a commit runs where [run] puts it.
 */
class PendingUndo(
    /** Calls the block after the delay; the function it returns cancels that. */
    private val timer: (delayMs: Long, block: () -> Unit) -> () -> Unit,
    /** Runs a commit (off the main thread in the app); its key leaves [hidden] once it ends. */
    private val run: (suspend () -> Unit) -> Unit,
    /** Shows the shared notice for [count] actions with Undo calling the function given; null count hides it. */
    private val announce: (count: Int?, undo: () -> Unit) -> Unit,
    private val delayMs: Long = DELAY_MS,
) {
    private class Pending(val key: String, val commit: suspend () -> Unit, val undo: () -> Unit)

    private val waiting = mutableListOf<Pending>()
    private var cancelTimer: (() -> Unit)? = null

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the actions waiting or being done: what the screens hide. Any thread. */
    val hidden: StateFlow<Set<String>> = _hidden

    /** How many actions the notice counts now. */
    val count: Int @Synchronized get() = waiting.size

    /**
     * Hides [key] now and does [commit] when the notice goes; [undo]
     * restores anything the screen changed besides hiding it. A key already
     * hidden is ignored (it is gone from the screen; a second tap cannot reach it).
     */
    @Synchronized
    fun enqueue(key: String, commit: suspend () -> Unit, undo: () -> Unit = {}) {
        if (key in _hidden.value) return
        waiting += Pending(key, commit, undo)
        _hidden.update { it + key }
        // The notice stays its full time after the last action.
        cancelTimer?.invoke()
        cancelTimer = timer(delayMs) { commitAll() }
        announce(waiting.size, ::undoAll)
    }

    /** Undo: everything waiting is back as it was, nothing of it done. */
    @Synchronized
    fun undoAll() {
        if (waiting.isEmpty()) return
        val back = waiting.toList()
        waiting.clear()
        cancelTimer?.invoke()
        cancelTimer = null
        val keys = back.map { it.key }.toSet()
        _hidden.update { it - keys }
        back.forEach { it.undo() }
        announce(null) {}
    }

    /** Does everything waiting now: the notice's time is up, or the app is going away. */
    @Synchronized
    fun commitAll() {
        if (waiting.isEmpty()) return
        val due = waiting.toList()
        waiting.clear()
        cancelTimer?.invoke()
        cancelTimer = null
        announce(null) {}
        due.forEach { pending ->
            run {
                try {
                    pending.commit()
                } finally {
                    // Done or failed: a failed one shows again (its commit says why).
                    _hidden.update { it - pending.key }
                }
            }
        }
    }

    companion object {
        /** As long as a notice with a button stays ([ru.adsrv.frpjump.notices.TopNotice.ACTION_MS]). */
        const val DELAY_MS = 6_000L

        /** The key of [profile]'s connection [id]. */
        fun connectionKey(profile: String, id: String) = "connection:$profile/$id"

        /** Ids of [profile]'s connections among [hidden]. */
        fun connectionIds(hidden: Set<String>, profile: String): Set<String> {
            val prefix = "connection:$profile/"
            return hidden.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        }

        /** The key of snippet [id]. */
        fun snippetKey(id: String) = "snippet:$id"
    }
}
