package ru.adsrv.frpjump.undo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.ports.profilePortPrefix
import ru.adsrv.frpjump.tunnel.SessionKind

/** What an action waiting for Undo does, as its notice counts it: "Deleted: 2 · Disconnected: 1". */
enum class UndoKind { DELETED, DISCONNECTED, CLOSED }

/** The notice's words for [counts] (actions waiting, by kind), in [UndoKind] order, joined by " · "; [word] says one kind's count. */
fun undoText(counts: Map<UndoKind, Int>, word: (UndoKind, Int) -> String): String =
    UndoKind.entries.mapNotNull { kind -> counts[kind]?.takeIf { it > 0 }?.let { word(kind, it) } }.joinToString(" · ")

/**
 * "Undo instead of confirm" (docs/contract.md, UI rules): a destructive
 * action is only hidden at once; it is done ([enqueue]'s commit) when the
 * notice with Undo goes, [delayMs] after it is shown ([noticeShown]) for
 * the last action, and at most [capMs] after that action when the notice
 * cannot show. Commits of a batch run one after another. Several in
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
    /** Runs a batch's commits, one after another (in the app's scope); each key leaves [hidden] once its commit ends. */
    private val run: (suspend () -> Unit) -> Unit,
    /** Shows the shared notice for the actions waiting, counted by kind, with Undo calling the function given; null hides it. */
    private val announce: (counts: Map<UndoKind, Int>?, undo: () -> Unit) -> Unit,
    private val delayMs: Long = DELAY_MS,
    private val capMs: Long = CAP_MS,
) {
    private class Pending(val key: String, val kind: UndoKind, val commit: suspend () -> Unit, val undo: () -> Unit)

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
     * [kind] is what the notice calls it.
     */
    @Synchronized
    fun enqueue(key: String, commit: suspend () -> Unit, undo: () -> Unit = {}, kind: UndoKind = UndoKind.DELETED) {
        if (key in _hidden.value) return
        waiting += Pending(key, kind, commit, undo)
        _hidden.update { it + key }
        // Until the notice for it shows, only the cap runs; then its full time.
        restartTimer(capMs)
        announceWaiting()
    }

    private fun announceWaiting() = announce(waiting.groupingBy { it.kind }.eachCount(), ::undoAll)

    /**
     * Takes back the action of [key] alone, as Undo would, while it still
     * waits: the notice counts the rest, or goes with none left. False when
     * it does not wait (never enqueued, already done or being done).
     */
    @Synchronized
    fun withdraw(key: String): Boolean {
        val pending = waiting.firstOrNull { it.key == key } ?: return false
        waiting.remove(pending)
        _hidden.update { it - key }
        pending.undo()
        if (waiting.isEmpty()) {
            cancelTimer?.invoke()
            cancelTimer = null
            announce(null) {}
        } else {
            announceWaiting()
        }
        return true
    }

    /** Whether the action of [key] is being done now: its notice went, its commit has not ended. */
    @Synchronized
    fun committing(key: String): Boolean = key in _hidden.value && waiting.none { it.key == key }

    /** Suspends until the action of [key] is neither waiting nor being done. */
    suspend fun awaitDone(key: String) {
        hidden.first { key !in it }
    }

    /** The keys waiting for the notice to go, in order. */
    @Synchronized
    fun waitingKeys(): List<String> = waiting.map { it.key }

    /** The notice for what waits now is on screen: it stays its full time from here. */
    @Synchronized
    fun noticeShown() {
        if (waiting.isNotEmpty()) restartTimer(delayMs)
    }

    private fun restartTimer(ms: Long) {
        cancelTimer?.invoke()
        cancelTimer = timer(ms) { commitAll() }
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
        // One after another: two deletes on one tunnel see each other's result.
        run {
            due.forEach { pending ->
                try {
                    pending.commit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A commit says why it failed itself; the others still run.
                    Log.w(TAG, "commit of ${pending.key} failed", e)
                } finally {
                    // Done or failed: a failed one shows again.
                    _hidden.update { it - pending.key }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PendingUndo"

        /** As long as a notice with a button stays ([ru.adsrv.frpjump.notices.TopNotice.ACTION_MS]). */
        const val DELAY_MS = 6_000L

        /** The longest an action waits when its notice cannot show (queued, no screen to draw it). */
        const val CAP_MS = 12_000L

        /** The key of [profile]'s connection [id]. */
        fun connectionKey(profile: String, id: String) = "connection:$profile/$id"

        /** Ids of [profile]'s connections among [hidden]. */
        fun connectionIds(hidden: Set<String>, profile: String): Set<String> {
            val prefix = "connection:$profile/"
            return hidden.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        }

        /** The key of pin [filter] of [profile]'s connection [connectionId]. */
        fun mqttPinKey(profile: String, connectionId: String, filter: String) = "mqttpin:$profile/$connectionId\u0000$filter"

        /** The pins of [profile]'s connection [connectionId] among [hidden], as topic filters. */
        fun mqttPins(hidden: Set<String>, profile: String, connectionId: String): Set<String> {
            val prefix = "mqttpin:$profile/$connectionId\u0000"
            return hidden.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        }

        /** The key of snippet [id]. */
        fun snippetKey(id: String) = "snippet:$id"

        /** The key of [profile]'s tunnel to [device]:[port], whose disconnect waits. */
        fun tunnelKey(profile: String, device: String, port: Int) = "tunnel:" + profilePortKey(profile, device, port)

        /** The tunnels of every profile among [hidden], as [profilePortKey]s: dark, their disconnect waiting. */
        fun darkTunnels(hidden: Set<String>): Set<String> =
            hidden.filter { it.startsWith("tunnel:") }.map { it.removePrefix("tunnel:") }.toSet()

        /** [darkTunnels] of [profile], as its tunnels' own keys ([ru.adsrv.frpjump.ports.localPortKey]). */
        fun darkTunnels(hidden: Set<String>, profile: String): Set<String> {
            val prefix = profilePortPrefix(profile)
            return darkTunnels(hidden).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        }

        /** The key of tmux session [name] of [user] on [profile]'s [device]:[port] that no tab holds, whose end waits. */
        fun tmuxKey(profile: String, device: String, port: Int, user: String, name: String) =
            "tmux:" + profilePortKey(profile, device, port) + "/$user/$name"

        /** The key of the session [id] of [kind] (ids are per kind), whose close waits. */
        fun sessionKey(kind: SessionKind, id: Int) = "session:${kind.name.lowercase()}/$id"
    }
}
