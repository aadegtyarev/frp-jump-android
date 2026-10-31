package ru.adsrv.frpjump.ui.mqtt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.MqttSession
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.mqtt.PinMask
import ru.adsrv.frpjump.mqtt.SnapshotState
import ru.adsrv.frpjump.mqtt.TopicNode
import ru.adsrv.frpjump.mqtt.TopicProblem
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.pinFilter
import ru.adsrv.frpjump.mqtt.pinnedMasks
import ru.adsrv.frpjump.mqtt.topicProblem
import ru.adsrv.frpjump.mqtt.pinnedAt

/** One row of a level: a node of the tree, or the root's `$SYS` before anything was seen there. */
data class TopicRow(val node: TopicNode, val pinned: Boolean) {
    /** Opened by a tap, as a folder: it has children, or it is the root's `$SYS` (always a branch). */
    val isBranch: Boolean get() = node.hasChildren || (node.path.levels.size == 1 && node.name == SYS)
}

/** A topic search found: its path relative to the level searched, and the node. */
data class SearchHit(val relative: MqttPath, val node: TopicNode)

/** What the open topic card shows: the node at [path] now (null when nothing is seen there), its last values and pins. */
data class CardState(
    val path: MqttPath,
    val node: TopicNode?,
    /** Newest first, at most [ru.adsrv.frpjump.mqtt.TopicTree.HISTORY]. */
    val history: List<TopicValue>,
    /** The masks [path] is pinned with. */
    val pinned: List<PinMask>,
) {
    /** A branch card (counts) rather than only a topic's: it has children, or it is the root's `$SYS`. */
    val isBranch: Boolean get() = node?.hasChildren == true || (path.levels.size == 1 && path.name == SYS)
}

data class MqttUiState(
    val state: MqttState = MqttState.Connecting,
    val snapshot: SnapshotState = SnapshotState.Idle,
    val path: MqttPath = MqttPath.ROOT,
    val rows: List<TopicRow> = emptyList(),
    val topicCount: Int = 0,
    val limitReached: Boolean = false,
    /** The open branch's filter was refused by the broker (an ACL). */
    val branchRefused: Boolean = false,
    /** Search hits for the query, at most [MAX_HITS]; [hitsTotal] counts them all. */
    val hits: List<SearchHit> = emptyList(),
    val hitsTotal: Int = 0,
    /** When the rows were read, for their ages. */
    val now: Long = 0,
    /** Since when this level is shown: only changes after it are highlighted. */
    val shownSince: Long = 0,
    /** The session went because its tunnel was disconnected. */
    val closedByTunnel: Boolean = false,
    /** The topic card open, or null. */
    val card: CardState? = null,
)

/**
 * The explorer screen's view of one app-scoped [MqttSession]: the open
 * level as rows, read from the tree once a second while shown (the tree
 * has no change events), and the search hits. The session's open branch
 * is the screen's place, so returning to a session returns to its level.
 */
class MqttViewModel(private val app: FrpJumpApp, val sessionId: Int) : ViewModel() {
    val session: MqttSession? = app.mqtt.find(sessionId)

    /** What the search field holds. */
    val query = MutableStateFlow("")

    /** The topic scrolled to in its level, e.g. a typed one. */
    val focused = MutableStateFlow<MqttPath?>(null)

    /** The node whose card is open; kept here so it outlives a rotation. */
    val card = MutableStateFlow<MqttPath?>(null)

    val state: StateFlow<MqttUiState> = session?.let { s ->
        val ticks = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(REFRESH_MS)
            }
        }
        // Searching walks the whole level: again only when the query, the level or the number of topics changes, not every tick.
        val searches = combine(query.map { it.trim() }, s.branch, ticks.map { s.tree.topicCount }.distinctUntilChanged()) { needle, path, _ ->
            search(s, path, needle)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
        // A pin unpinned with Undo on screen is gone from the marks and the card until Undo brings it back.
        val pins = combine(s.pins, app.pendingUndo.hidden) { all, hidden ->
            val gone = PendingUndo.mqttPins(hidden, s.profile, s.connectionId)
            if (gone.isEmpty()) all else all.filter { it !in gone }
        }
        combine(s.state, s.snapshot, s.branch, pins, s.refused, searches, card, ticks) { values ->
            @Suppress("UNCHECKED_CAST")
            read(
                s,
                values[0] as MqttState,
                values[1] as SnapshotState,
                values[2] as MqttPath,
                values[3] as List<String>,
                values[4] as Set<String>,
                values[5] as Search,
                values[6] as MqttPath?,
                values[7] as Long,
            )
        }.flowOn(Dispatchers.Default)
    }.let { it ?: flowOf(MqttUiState(state = MqttState.Ended, closedByTunnel = app.mqtt.closedByTunnel(sessionId))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MqttUiState(state = if (session == null) MqttState.Ended else MqttState.Connecting))

    /** The level last read and since when it is shown: a change before that is not highlighted. */
    private var shownPath: MqttPath? = null
    private var shownSince = 0L

    private fun read(
        s: MqttSession,
        state: MqttState,
        snapshot: SnapshotState,
        path: MqttPath,
        pins: List<String>,
        refused: Set<String>,
        search: Search,
        card: MqttPath?,
        now: Long,
    ): MqttUiState {
        if (path != shownPath) {
            shownPath = path
            shownSince = now
        }
        val children = s.tree.children(path)
        val rows = buildList {
            children.forEach { add(TopicRow(it, pinnedAt(it.path, pins))) }
            // `#` never brings `$` topics: the root always offers `$SYS`, which subscribes it when opened.
            val sys = MqttPath.ROOT.child(SYS)
            if (path.isRoot && children.none { it.path == sys }) add(TopicRow(TopicNode(sys, null, 0, 0, false), pinnedAt(sys, pins)))
        }
        // Hits of another level (the search not yet run again after a move) are not shown.
        val hits = if (search.path == path) search else Search(path, emptyList(), 0)
        return MqttUiState(
            state = state,
            snapshot = snapshot,
            path = path,
            rows = rows,
            topicCount = s.tree.topicCount,
            limitReached = s.tree.limitReached,
            branchRefused = !path.isRoot && (path.format() + "/#") in refused,
            hits = hits.hits,
            hitsTotal = hits.total,
            now = now,
            shownSince = shownSince,
            closedByTunnel = state == MqttState.Ended && app.mqtt.closedByTunnel(s.id),
            card = card?.let { CardState(it, s.tree.node(it), s.tree.history(it), pinnedMasks(it, pins)) },
        )
    }

    /** The hits of [needle] at [path]: at most [MAX_HITS], with their values as they were then. */
    private data class Search(val path: MqttPath, val hits: List<SearchHit>, val total: Int)

    private fun search(s: MqttSession, path: MqttPath, needle: String): Search {
        if (needle.isEmpty()) return Search(path, emptyList(), 0)
        val found = s.tree.search(path, needle)
        val hits = found.take(MAX_HITS).mapNotNull { rel -> s.tree.node(MqttPath(path.levels + rel.levels))?.let { SearchHit(rel, it) } }
        return Search(path, hits, found.size)
    }

    /** Opens the level [path]. */
    fun open(path: MqttPath) {
        focused.value = null
        session?.open(path)
    }

    /** Back: up a level; false at the root, where Back leaves. */
    fun up(): Boolean {
        val path = session?.branch?.value ?: return false
        if (path.isRoot) return false
        open(path.parent)
        return true
    }

    /** Opens the card of the topic or branch at [path] (a topic's row, a branch's "⋮", a search hit). */
    fun openCard(path: MqttPath) {
        card.value = path
    }

    fun closeCard() {
        card.value = null
        focused.value = null
    }

    /**
     * Shows the topic at [path] in its level: that level opens, the
     * topic's row is scrolled to and its card opens (a typed topic without
     * children).
     */
    fun reveal(path: MqttPath) {
        session?.open(path.parent)
        focused.value = path
        card.value = path
    }

    /** Pins [path] with [mask]; returns the topic filter pinned. */
    fun pin(path: MqttPath, mask: PinMask): String = pinFilter(path, mask).also { filter ->
        session?.let { app.mqtt.pin(it.profile, it.connectionId, filter) }
    }

    /**
     * Unpins [path]'s pin with [mask] with Undo (UI rules): it is gone from
     * the screen at once and unsubscribed when the notice goes; Undo puts it
     * back where it was.
     */
    fun unpin(path: MqttPath, mask: PinMask) {
        val s = session ?: return
        val filter = pinFilter(path, mask)
        val mqtt = app.mqtt
        // Pinned again while the notice is up: the pin stays (it shows once the notice goes).
        val mark = mqtt.pinMark(s.profile, s.connectionId, filter)
        app.pendingUndo.enqueue(PendingUndo.mqttPinKey(s.profile, s.connectionId, filter), commit = {
            withContext(Dispatchers.Main) { mqtt.unpin(s.profile, s.connectionId, filter, mark) }
        })
    }

    /**
     * A path typed in the path bar, taken as is (`/devices/x` and
     * `devices/x` are different places): a topic without children opens its
     * level with the topic shown; anything else opens as a level, empty
     * when nothing has been seen there. Returns why it cannot be opened,
     * or null once it is.
     */
    fun openTyped(text: String): TopicProblem? {
        topicProblem(text)?.let { return it }
        val path = MqttPath.parse(text)
        val node = session?.tree?.node(path)
        if (node != null && node.value != null && !node.hasChildren) reveal(path) else open(path)
        return null
    }

    /**
     * "Pin a branch…" of a broker refusing `#`: [text], taken as is like a
     * typed path, is pinned with `#` and opened. Returns why it cannot be,
     * or null once it is.
     */
    fun pinBranch(text: String): TopicProblem? {
        topicProblem(text, suffix = "/#")?.let { return it }
        val path = MqttPath.parse(text)
        session?.pin(path.format() + "/#")
        open(path)
        return null
    }

    fun refreshTree() = session?.refreshTree()

    fun retry() = session?.retry()

    fun login(user: String, password: String, remember: Boolean) = session?.login(user, password, remember)

    /** "Close session": disconnects for good; the tunnel stays. */
    fun closeSession() = app.mqtt.close(sessionId)

    companion object {
        /** How often the open level is read again while shown. */
        const val REFRESH_MS = 1_000L

        /** Search hits listed at most. */
        const val MAX_HITS = 500
    }
}

internal const val SYS = "\$SYS"
