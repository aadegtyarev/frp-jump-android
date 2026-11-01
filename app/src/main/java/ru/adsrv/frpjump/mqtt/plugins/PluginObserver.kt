package ru.adsrv.frpjump.mqtt.plugins

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.mqtt.MqttSession
import ru.adsrv.frpjump.mqtt.SnapshotEnd
import ru.adsrv.frpjump.mqtt.SnapshotState
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WB_META_FILTERS
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbSeenIn
import java.util.concurrent.ConcurrentHashMap

/**
 * The Wiren Board plugin's filters a session holds, by the WB contract:
 * none with the plugin off; the meta filters once a control's meta was
 * [seen], or once a snapshot ended short or `#` was refused (the meta may
 * have been missed); none after a whole tree without meta. Null: left as
 * held (a snapshot still reading).
 */
fun wbFilters(on: Boolean, seen: Boolean, snapshot: SnapshotState): List<String>? = when {
    !on -> emptyList()
    seen -> WB_META_FILTERS
    snapshot == SnapshotState.Done(SnapshotEnd.QUIET) -> emptyList()
    snapshot is SnapshotState.Done || snapshot == SnapshotState.Refused -> WB_META_FILTERS
    else -> null
}

/**
 * Applies the plugin settings to every MQTT session, with or without an
 * explorer screen (docs/contract.md scenario 12, "Where the change is
 * applied"): it lives with the sessions, watches [store], resolves each
 * plugin for the session's connection, and drops or takes the plugin's
 * filters. Screens only write the store; they decide the tab and what is
 * shown.
 */
class PluginObserver(
    private val store: PluginStore,
    private val sessions: StateFlow<List<MqttSession>>,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Per session id: Wiren Board meta was seen in it (kept while the plugin is turned off and on). */
    private val seen = ConcurrentHashMap<Int, MutableStateFlow<Boolean>>()

    /** Touched by the sessions' collector only. */
    private val watching = HashMap<Int, Job>()

    init {
        scope.launch(io) {
            sessions.collect { list ->
                val ids = list.map { it.id }.toSet()
                watching.keys.filter { it !in ids }.forEach { watching.remove(it)?.cancel() }
                // Against the sessions now, not this (maybe older) list: a session just opened keeps its mark.
                val live = liveIds()
                seen.keys.removeAll { it !in live }
                list.filter { it.id !in watching }.forEach { s -> watching[s.id] = launch { watch(s) } }
            }
        }
    }

    /**
     * Whether session [sessionId] has shown Wiren Board meta; the explorer
     * marks it too when it finds meta. A session that is gone gets a mark
     * of its own that is not kept. Any thread.
     */
    fun wbSeen(sessionId: Int): MutableStateFlow<Boolean> =
        seen[sessionId] ?: if (sessionId in liveIds()) seen.getOrPut(sessionId) { MutableStateFlow(false) } else MutableStateFlow(false)

    /** The watch of session [sessionId], if it is watched; for tests. */
    internal fun watchOf(sessionId: Int): Job? = watching[sessionId]

    private fun liveIds(): Set<Int> = sessions.value.mapTo(HashSet()) { it.id }

    private suspend fun watch(s: MqttSession) {
        val key = pluginKey(s.profile, s.connectionId)
        val known = wbSeen(s.id)
        combine(store.changes, s.snapshot, known) { _, snapshot, k -> snapshot to k }.collect { (snapshot, k) ->
            // One failed pass must not end the watch: the next change applies again.
            runCatching {
                val on = store.get().isOn(key, PluginIds.WIREN_BOARD)
                // The tree may hold meta no screen has read yet: looked up first, so held filters are never dropped and taken again.
                val seenNow = on && (k || wbSeenIn(s.tree))
                if (seenNow && !k) known.value = true
                wbFilters(on, k || seenNow, snapshot)?.let(s::setPluginFilters)
            }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "plugin settings not applied: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "PluginObserver"
    }
}
