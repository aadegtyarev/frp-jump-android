package ru.adsrv.frpjump.ui.mqtt

import android.util.Log
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.MqttSession
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.mqtt.PinMask
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.mqtt.PublishRequest
import ru.adsrv.frpjump.mqtt.SentTopic
import ru.adsrv.frpjump.mqtt.wasSent
import ru.adsrv.frpjump.mqtt.SnapshotState
import ru.adsrv.frpjump.mqtt.SnapshotEnd
import ru.adsrv.frpjump.mqtt.TopicNode
import ru.adsrv.frpjump.mqtt.TopicProblem
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.pinFilter
import ru.adsrv.frpjump.mqtt.pinnedMasks
import ru.adsrv.frpjump.mqtt.topicProblem
import ru.adsrv.frpjump.mqtt.pinnedAt
import ru.adsrv.frpjump.mqtt.SysRowState
import ru.adsrv.frpjump.mqtt.sysRowState
import ru.adsrv.frpjump.mqtt.searchScope
import ru.adsrv.frpjump.mqtt.FilterOffer
import ru.adsrv.frpjump.mqtt.PatternProblem
import ru.adsrv.frpjump.mqtt.SearchQuery
import ru.adsrv.frpjump.mqtt.filterOffer
import ru.adsrv.frpjump.mqtt.parseSearch
import ru.adsrv.frpjump.mqtt.FilterRefusal
import ru.adsrv.frpjump.mqtt.SubscriptionRow
import ru.adsrv.frpjump.mqtt.rootedPin
import ru.adsrv.frpjump.mqtt.maskCoveredBy
import ru.adsrv.frpjump.mqtt.subscriptionRows
import ru.adsrv.frpjump.mqtt.typedFilterRefusal
import ru.adsrv.frpjump.mqtt.plugins.Rule
import ru.adsrv.frpjump.mqtt.plugins.STATUS_COMMAND_PLUGIN
import ru.adsrv.frpjump.mqtt.plugins.rulesInForce
import ru.adsrv.frpjump.mqtt.plugins.ConnectionPlugins
import ru.adsrv.frpjump.mqtt.plugins.wbTabAbsent
import ru.adsrv.frpjump.mqtt.plugins.wbTabAppeared
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WB_CONTROL_META_PATTERN
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WB_DEVICE_META_PATTERN
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WB_META_FILTERS
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbDevice
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbDeviceView
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbDevice
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbDevicePath
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbDevicePattern
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbDevices
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbSeen
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbTexts
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWidget
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWrite
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WriteView
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.awaitsEcho
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.currentValue
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.writeBlocked
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.widgetOf
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.writeView
import kotlinx.coroutines.flow.update

/** The explorer's tabs under its top bar, in their order; the third is the Wiren Board plugin's, shown only with it. */
enum class ExplorerTab { TREE, SUBSCRIPTIONS, WB_DEVICES }

/**
 * One row of a level: a node of the tree, or the root's `$SYS` before
 * anything was seen there, with [sys] saying why.
 */
data class TopicRow(val node: TopicNode, val pinned: Boolean, val sys: SysRowState? = null) {
    /** Drawn as a folder with "⋮" for its card: it has children, or it is the root's `$SYS` (always a branch). */
    val isBranch: Boolean get() = node.hasChildren || (node.path.levels.size == 1 && node.name == SYS)

    /** A tap opens its level: a branch without a value of its own. One with a value opens its card, which opens the branch. */
    val opensLevel: Boolean get() = isBranch && node.value == null
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
    /** The masks not pinned that a broader pin already delivers, each with that pin. */
    val coveredBy: Map<PinMask, String> = emptyMap(),
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
    /**
     * Where the search looks and what the hits are relative to: the open
     * level and below, or the root when widened to the whole tree or for a
     * pattern starting with `/`.
     */
    val searchAt: MqttPath = MqttPath.ROOT,
    /** The query is a pattern (`* ? + #`): the syntax line shows. */
    val searchPattern: Boolean = false,
    /** Why the pattern cannot be searched; no hits then. */
    val searchProblem: PatternProblem? = null,
    /** Subscribing the pattern's filter, under its hits; null for a substring query. */
    val filterOffer: FilterOffer? = null,
    /** A pattern that found nothing: how many it finds ignoring case (0 when it found something). */
    val hitsIgnoringCase: Int = 0,
    /** Search hits for the query under [searchAt], at most [MAX_HITS]; [hitsTotal] counts them all. */
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
    val tab: ExplorerTab = ExplorerTab.TREE,
    /** The connection's pins, in order, without those waiting for Undo. */
    val pins: List<String> = emptyList(),
    /** The Subscriptions tab's rows; read only while it is shown. */
    val subscriptions: List<SubscriptionRow> = emptyList(),
    /** The WB devices tab is in the row: the plugin is on and its meta was seen (or is awaited, see [wbReading]). */
    val wbTab: Boolean = false,
    /** The connection opened on the WB devices tab and the snapshot is still read: "Reading devices…". */
    val wbReading: Boolean = false,
    /** The devices list; read only while the tab shows it. */
    val wbDevices: List<WbDevice> = emptyList(),
    /** The device opened in the tab, by id; null on the list. */
    val wbDeviceId: String? = null,
    /** The open device read now; null when nothing of it is seen. */
    val wbDevice: WbDeviceView? = null,
    /** Where each control's last write stands, by control id; a control without one is not listed. */
    val wbAnswers: Map<String, WriteView> = emptyMap(),
    /** The last value sent to each `wo-switch` this session, by status topic. */
    val wbSent: Map<MqttPath, String> = emptyMap(),
) {
    /** The tabs in the row, in order. */
    val tabs: List<ExplorerTab> get() = if (wbTab) ExplorerTab.entries else listOf(ExplorerTab.TREE, ExplorerTab.SUBSCRIPTIONS)

    /** The tab shown: the WB devices tab falls back to Tree once it is gone. */
    val shownTab: ExplorerTab get() = if (tab == ExplorerTab.WB_DEVICES && !wbTab) ExplorerTab.TREE else tab
}

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

    /** The search was widened to the whole tree ("Search the whole tree"); off again when the search closes. */
    val searchWhole = MutableStateFlow(false)

    /** The topic scrolled to in its level, e.g. a typed one. */
    val focused = MutableStateFlow<MqttPath?>(null)

    /** The node whose card is open; kept here so it outlives a rotation. */
    val card = MutableStateFlow<MqttPath?>(null)

    /** The tab shown; kept here so it outlives a rotation. */
    val tab = MutableStateFlow(ExplorerTab.TREE)

    /** The phone's language code (`ru`), for the titles of the WB devices tab; set by the screen. */
    val language = MutableStateFlow("en")

    /** The device open in the WB devices tab, by id; null on its list. */
    val wbDeviceId = MutableStateFlow<String?>(null)

    /** The connection's plugin settings; null until read. */
    private val plugins = MutableStateFlow<ConnectionPlugins?>(null)

    /** Wiren Board meta was seen in this session: the WB devices tab shows (with the plugin on). */
    private val wbSeenNow = MutableStateFlow(false)

    /** The explorer opened on the WB devices tab (the last session showed it): it waits for the snapshot. */
    private val openedOnWb = MutableStateFlow(false)

    /** Writes in flight or answered, by the control's status topic; one per control, the newest. */
    private val wbWrites = MutableStateFlow<Map<MqttPath, WbWrite>>(emptyMap())

    /** The last value sent to each `wo-switch` in this session, by its status topic. */
    private val wbSent = MutableStateFlow<Map<MqttPath, String>>(emptyMap())

    val state: StateFlow<MqttUiState> = session?.let { s ->
        val ticks = flow {
            while (true) {
                emit(System.currentTimeMillis())
                // A write waiting on a control is read more often, so its answer shows as it comes.
                delay(if (writesPending()) WRITE_REFRESH_MS else REFRESH_MS)
            }
        }
        // Searching walks the whole level: again only when the query, the level or the number of topics changes, not every tick.
        val scope = combine(s.branch, searchWhole) { path, whole -> searchScope(path, whole) }
        val searches = combine(query.map { it.trim() }, scope, ticks.map { s.tree.topicCount }.distinctUntilChanged()) { needle, path, _ ->
            search(s, path, needle)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
        // A pin unpinned with Undo on screen is gone from the marks and the card until Undo brings it back.
        val pins = combine(s.pins, app.pendingUndo.hidden) { all, hidden ->
            val gone = PendingUndo.mqttPins(hidden, s.profile, s.connectionId)
            if (gone.isEmpty()) all else all.filter { it !in gone }
        }
        val wb = combine(plugins, language, wbDeviceId, openedOnWb, wbWrites) { p, lang, device, opened, _ -> WbAsk(p?.wirenBoard?.on == true, lang, device, opened) }
        combine(s.state, s.snapshot, s.branch, pins, s.refused, searches, card, ticks, searchWhole, tab, wb) { values ->
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
                values[8] as Boolean,
                values[9] as ExplorerTab,
                values[10] as WbAsk,
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
        whole: Boolean,
        tab: ExplorerTab,
        wb: WbAsk,
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
            if (path.isRoot && children.none { it.path == sys }) {
                add(TopicRow(TopicNode(sys, null, 0, 0, false), pinnedAt(sys, pins), sysRowState(pins, refused)))
            }
        }
        // Hits of another place (the search not yet run again after a move) are not shown.
        val searchAt = searchScope(path, whole)
        val hits = if (search.scope == searchAt) search else Search(searchAt, searchAt, search.query, emptyList(), 0, 0)
        return MqttUiState(
            state = state,
            snapshot = snapshot,
            path = path,
            rows = rows,
            topicCount = s.tree.topicCount,
            limitReached = s.tree.limitReached,
            branchRefused = !path.isRoot && (path.format() + "/#") in refused,
            searchAt = hits.at,
            searchPattern = hits.query !is SearchQuery.Substring,
            searchProblem = (hits.query as? SearchQuery.Invalid)?.problem,
            filterOffer = filterOffer(hits.query, hits.scope, pins, open = state != MqttState.Ended),
            hitsIgnoringCase = hits.withoutCase,
            hits = hits.hits,
            hitsTotal = hits.total,
            now = now,
            shownSince = shownSince,
            closedByTunnel = state == MqttState.Ended && app.mqtt.closedByTunnel(s.id),
            card = card?.let { CardState(it, s.tree.node(it), s.tree.history(it), pinnedMasks(it, pins), maskCoveredBy(it, pins)) },
            tab = tab,
            pins = pins,
            subscriptions = if (tab == ExplorerTab.SUBSCRIPTIONS) subscriptionRows(pins, s.tree) else emptyList(),
        ).let { readWb(s, it, wb) }
    }

    /** What the WB devices tab needs: the plugin on, the phone's language, the open device. */
    private data class WbAsk(val on: Boolean, val language: String, val device: String?, val openedOnWb: Boolean)

    /**
     * The WB devices tab of [ui]: whether it shows (the plugin on and a
     * control's meta seen, looked for each read until it is), and while it
     * is the tab shown, the devices list or the open device.
     */
    private fun readWb(s: MqttSession, ui: MqttUiState, wb: WbAsk): MqttUiState {
        // Set again below while a device with a waiting write is shown.
        writeWaiting = false
        if (!wb.on) return ui
        if (!wbSeenNow.value && seenInTree(s)) wbSeenNow.value = true
        val seen = wbSeenNow.value
        val finished = ui.snapshot is SnapshotState.Done || ui.snapshot == SnapshotState.Refused || ui.state is MqttState.Failed || ui.state == MqttState.Ended
        val reading = !seen && wb.openedOnWb && !finished
        val present = seen || reading
        if (!present || ui.tab != ExplorerTab.WB_DEVICES) return ui.copy(wbTab = present, wbReading = reading)
        val device = wb.device
        if (device == null) return ui.copy(wbTab = true, wbReading = reading, wbDevices = deviceList(s, wb.language))
        val view = wbDevice(device, wbTexts(s.tree.values(wbDevicePattern(device))), wb.language)
        val now = System.currentTimeMillis()
        val writes = wbWrites.value
        val answers = view?.controls?.mapNotNull { c ->
            val write = writes[c.path] ?: return@mapNotNull null
            c.id to writeView(write, c.meta, s.tree.history(c.path), now)
        }?.toMap().orEmpty()
        writeWaiting = answers.values.any { it is WriteView.Sending }
        return ui.copy(wbTab = true, wbReading = reading, wbDeviceId = device, wbDevice = view, wbAnswers = answers, wbSent = wbSent.value)
    }

    /** A control's meta is in the tree now. */
    private fun seenInTree(s: MqttSession): Boolean = wbSeen(wbTexts(s.tree.values(WB_CONTROL_META_PATTERN)))

    /** The meta texts and language the devices list was last read from, and that list. */
    private var listTexts: Map<MqttPath, String>? = null
    private var listLanguage = ""
    private var list: List<WbDevice> = emptyList()

    /**
     * The devices list. The meta topics are gathered on every read (one
     * walk of `/devices/+`, no parsing), but parsed and sorted again only
     * when they or the language changed: meta changes rarely.
     */
    private fun deviceList(s: MqttSession, language: String): List<WbDevice> {
        val texts = wbTexts(s.tree.values(WB_DEVICE_META_PATTERN) + s.tree.values(WB_CONTROL_META_PATTERN))
        if (texts != listTexts || language != listLanguage) {
            listTexts = texts
            listLanguage = language
            list = wbDevices(texts, language)
        }
        return list
    }

    /**
     * The hits of [query] run for [scope] (the open level or the whole
     * tree), relative to [at] (the root for a pattern starting with `/`):
     * at most [MAX_HITS], with their values as they were then. A pattern
     * that found nothing is run once more ignoring case: [withoutCase].
     */
    private data class Search(val scope: MqttPath, val at: MqttPath, val query: SearchQuery, val hits: List<SearchHit>, val total: Int, val withoutCase: Int)

    private fun search(s: MqttSession, scope: MqttPath, needle: String): Search {
        val query = parseSearch(needle)
        val at = (query as? SearchQuery.Pattern)?.base(scope) ?: scope
        val found = when {
            needle.isEmpty() -> emptyList()
            query is SearchQuery.Pattern -> s.tree.match(at, query.levels)
            query is SearchQuery.Substring -> s.tree.search(at, needle)
            else -> emptyList()
        }
        val hits = found.take(MAX_HITS).mapNotNull { rel -> s.tree.node(MqttPath(at.levels + rel.levels))?.let { SearchHit(rel, it) } }
        val withoutCase = if (found.isEmpty() && query is SearchQuery.Pattern) s.tree.match(at, query.levels, ignoreCase = true).size else 0
        return Search(scope, at, query, hits, found.size, withoutCase)
    }

    /** Opens device [id] in the WB devices tab: its branch becomes the session's live one. */
    fun openWbDevice(id: String) {
        wbDeviceId.value = id
    }

    /** A write shown on the open device still has no outcome: the device is read more often. Set by each read. */
    @Volatile
    private var writeWaiting = false

    private fun writesPending(): Boolean = writeWaiting

    /**
     * Writes [payload] to [control]'s `/on` (QoS 1, not retained, whatever
     * the Status and command plugin says); the answer shows on the control.
     * A tap while the control's last write has no outcome yet is ignored, so
     * a double tap sends once. Whether an echo is awaited is decided on the
     * status topic's value now, not on the last read of the screen.
     */
    fun writeWb(control: WbControl, payload: String) {
        val s = session ?: return
        val now = System.currentTimeMillis()
        val history = s.tree.history(control.path)
        if (writeBlocked(wbWrites.value[control.path], control.meta, history, now)) return
        val widget = widgetOf(control.meta)
        val shown = if (widget == WbWidget.WO_SWITCH) wbSent.value[control.path] else currentValue(control.meta, history)
        val write = WbWrite(control.path, payload, awaitsEcho(control.meta, widget, shown, payload), now)
        wbWrites.update { it + (control.path to write) }
        writeWaiting = true
        s.publish(control.path.format() + "/on", payload.toByteArray(Charsets.UTF_8), qos = 1, retain = false) { answer ->
            val at = System.currentTimeMillis()
            // A wo-switch shows what it was last sent: only what left for the device counts.
            if (widget == WbWidget.WO_SWITCH && wasSent(answer)) wbSent.update { it + (control.path to payload) }
            // A newer write to the control replaced this one: its answer is not shown.
            wbWrites.update { all -> all[control.path]?.takeIf { it.sentAt == write.sentAt && it.asked == payload }?.let { all + (control.path to it.copy(answer = answer, answeredAt = at)) } ?: all }
        }
    }

    /** Back in a device: its list; the tree's branch is live again. */
    fun closeWbDevice() {
        wbDeviceId.value = null
    }

    /** Opens the level [path], in the tree. */
    fun open(path: MqttPath) {
        focused.value = null
        tab.value = ExplorerTab.TREE
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
        tab.value = ExplorerTab.TREE
        session?.open(path.parent)
        focused.value = path
        card.value = path
    }

    /** Pins [path] with [mask]; returns the topic filter pinned. */
    fun pin(path: MqttPath, mask: PinMask): String = pinFilter(path, mask).also { filter ->
        session?.let { app.mqtt.pin(it.profile, it.connectionId, filter) }
    }

    /** Pins the topic [filter] a search pattern makes ("Subscribe to this filter"). */
    fun subscribeFilter(filter: String) {
        session?.let { app.mqtt.pin(it.profile, it.connectionId, filter) }
    }

    /**
     * Unpins [path]'s pin with [mask] with Undo (UI rules): it is gone from
     * the screen at once and unsubscribed when the notice goes; Undo puts it
     * back where it was.
     */
    fun unpin(path: MqttPath, mask: PinMask) = unpinFilter(pinFilter(path, mask))

    /** Unpins the pin [filter] with Undo, as [unpin] does (a card's mask, or the search's filter). */
    fun unpinFilter(filter: String) {
        val s = session ?: return
        val mqtt = app.mqtt
        // Pinned again while the notice is up: the pin stays (it shows once the notice goes).
        val mark = mqtt.pinMark(s.profile, s.connectionId, filter)
        app.pendingUndo.enqueue(PendingUndo.mqttPinKey(s.profile, s.connectionId, filter), commit = {
            // No session of the connection open (this one closed): this one still takes it from the store and
            // from the list its Subscriptions tab shows; the next session subscribes without it.
            withContext(Dispatchers.Main) { mqtt.unpin(s.profile, s.connectionId, filter, mark, closed = s) }
        })
    }

    /** Whether pins can still be made: the session has not ended (a closed one is gone from the app's list). */
    private fun canPin(s: MqttSession) = s.state.value != MqttState.Ended && app.mqtt.find(s.profile, s.connectionId) === s

    /**
     * "Change mask" of the pin [filter] (one a card can name) to [mask];
     * returns the new filter, or null when nothing changed: no such pin,
     * or the session closed while the sheet was open.
     */
    fun changeMask(filter: String, mask: PinMask): String? {
        val s = session?.takeIf(::canPin) ?: return null
        val rooted = rootedPin(filter) ?: return null
        return pinFilter(rooted.topic, mask).also { app.mqtt.replacePin(s.profile, s.connectionId, filter, it) }
    }

    /** "+" on the Subscriptions tab: pins the typed topic filter [text]; returns why it cannot be, or null once it is. */
    fun subscribeTyped(text: String): FilterRefusal? {
        val s = session
        typedFilterRefusal(text, state.value.pins, open = s != null && canPin(s))?.let { return it }
        s ?: return FilterRefusal.SessionClosed
        app.mqtt.pin(s.profile, s.connectionId, text)
        return null
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

    /**
     * What the publish sheet remembers of this connection's topics, the most
     * recently sent first; null until read.
     */
    val published = MutableStateFlow<List<SentTopic>?>(null)

    init {
        session?.let { s ->
            viewModelScope.launch(Dispatchers.IO) {
                val stored = app.mqttPublished.get(s.profile, s.connectionId)
                // A send remembered meanwhile already holds what was read and more.
                published.compareAndSet(null, stored)
            }
            watchWirenBoard(s)
        }
    }

    /**
     * The Wiren Board plugin's side of the session: its settings read
     * again on every change; a connection whose last session showed the
     * tab opens on it; while the tab shows, the meta filters are held;
     * the tab appearing is remembered (and turns the first plugin's preset
     * on, once), a snapshot without meta forgets it; an open device is the
     * session's live branch.
     */
    private fun watchWirenBoard(s: MqttSession) {
        val store = app.mqttPlugins
        viewModelScope.launch(Dispatchers.IO) {
            var first = true
            store.changes.collect {
                val p = store.get(s.profile, s.connectionId)
                plugins.value = p
                if (first) {
                    first = false
                    // Opens on the tab only before the owner picked one.
                    if (p.wirenBoard.on && p.wirenBoard.tabShown && tab.value == ExplorerTab.TREE) {
                        openedOnWb.value = true
                        tab.value = ExplorerTab.WB_DEVICES
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(plugins, wbSeenNow, s.snapshot) { p, seen, snapshot -> Triple(p, seen, snapshot) }.collect { (p, known, snapshot) ->
                p ?: return@collect
                val on = p.wirenBoard.on
                if (!on) {
                    // Off: no filters, and turned on again later the tab does not take the screen by itself.
                    s.setPluginFilters(emptyList())
                    tab.compareAndSet(ExplorerTab.WB_DEVICES, ExplorerTab.TREE)
                    return@collect
                }
                // The tree may hold meta the screen has not read yet (a session entered again, a snapshot just ended):
                // looked up first, so the filters a session already holds are never dropped and taken again.
                val seen = known || seenInTree(s)
                if (seen && !known) wbSeenNow.value = true
                val complete = snapshot == SnapshotState.Done(SnapshotEnd.QUIET)
                when {
                    // Only a whole tree without meta drops them.
                    complete && !seen -> s.setPluginFilters(emptyList())
                    // A snapshot cut short (time, topic limit) or `#` refused may have missed the meta: asked for,
                    // so the tab can still appear from them.
                    seen || snapshot is SnapshotState.Done || snapshot == SnapshotState.Refused -> s.setPluginFilters(WB_META_FILTERS)
                    // Meanwhile (a snapshot or a refresh still reading) they are left as held.
                }
                // Read whole without meta: the tab is gone, and appearing later it does not take the screen.
                if (!seen && snapshot is SnapshotState.Done) tab.compareAndSet(ExplorerTab.WB_DEVICES, ExplorerTab.TREE)
                val change: ((ConnectionPlugins) -> ConnectionPlugins)? = when {
                    seen && wbTabAppeared(p) != p -> ::wbTabAppeared
                    // Only a snapshot that read the whole tree says there is no meta; a cut one may have missed it.
                    !seen && p.wirenBoard.tabShown && complete -> ::wbTabAbsent
                    else -> null
                }
                change?.let { c -> runCatching { store.update(s.profile, s.connectionId, c) }.onFailure { Log.w(TAG, "plugin settings not saved: ${it.message}") } }
            }
        }
        viewModelScope.launch {
            // The device shown is live; on the list, on another tab or with the plugin off, the tree's level is.
            combine(tab, wbDeviceId, plugins, wbSeenNow, openedOnWb) { t, device, p, seen, opened ->
                device.takeIf { t == ExplorerTab.WB_DEVICES && p?.wirenBoard?.on == true && (seen || opened) }
            }
                .distinctUntilChanged()
                .collect { device -> s.openView(device?.let(::wbDevicePath)) }
        }
    }

    /**
     * The plugin's meta filters stay with the session after the screen goes
     * (they are few and change rarely; the next screen finds them held);
     * turning the plugin off drops them, also from the connection's settings.
     */
    override fun onCleared() {
        // The tree's level is live again for whoever comes back to the session.
        session?.openView(null)
    }

    /**
     * The rules of the "Status and command topics" plugin in force for this
     * connection, own rules first, then the presets on; empty with the
     * plugin off. Read again when the settings change; an own rule deleted
     * with Undo on screen is left out until Undo brings it back.
     */
    val commandRules: StateFlow<List<Rule>> = session?.let { s ->
        combine(app.mqttPlugins.changes, app.pendingUndo.hidden) { _, hidden ->
            val settings = app.mqttPlugins.get(s.profile, s.connectionId).statusCommand
            rulesInForce(settings, STATUS_COMMAND_PLUGIN.presets, PendingUndo.mqttRules(hidden, s.profile, s.connectionId))
        }.flowOn(Dispatchers.IO)
    }.let { it ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Send of the publish sheet (the topic checked by it); [done] gets the
     * answer on the main thread. A message that left the phone is
     * remembered for the topic in the sheet's field (also when a rule sent
     * it to a command topic): the payload, retain, QoS and format.
     */
    fun publish(request: PublishRequest, done: (PublishAnswer) -> Unit) {
        val s = session ?: return done(PublishAnswer.Failed(PublishFailure.NOT_CONNECTED))
        val memory = app.mqttPublished
        s.publish(request.topic, request.payload.toByteArray(Charsets.UTF_8), request.qos, request.retain) { answer ->
            viewModelScope.launch(Dispatchers.Main) { done(answer) }
            if (wasSent(answer)) {
                viewModelScope.launch(Dispatchers.IO) {
                    // Under the store's lock, so overlapping sends show in the order they were stored.
                    synchronized(memory) {
                        // A failed write (a full disk) keeps what was sent in memory; the send itself went.
                        runCatching { with(request) { memory.sent(s.profile, s.connectionId, field, payload, retain, qos, json) } }
                            .onFailure { Log.w(TAG, "publish memory not saved: ${it.message}") }
                        published.value = memory.get(s.profile, s.connectionId)
                    }
                }
            }
        }
    }

    fun refreshTree() = session?.refreshTree()

    fun retry() = session?.retry()

    fun login(user: String, password: String, remember: Boolean) = session?.login(user, password, remember)

    /** "Close session": with Undo, as on the Sessions tab; it disconnects for good when that goes. The tunnel stays. */
    fun closeSession() = app.closeSessions.mqtt(sessionId)

    /** Leaving the explorer: the count for "N sessions keep running", the first time only (see [OpenSessions.leftRunning]). */
    fun leftRunning(): Int? = app.sessions.leftRunning()

    companion object {
        /** How often the open level is read again while shown. */
        const val REFRESH_MS = 1_000L

        /** How often the open device is read while a write waits for its answer. */
        const val WRITE_REFRESH_MS = 250L

        /** Search hits listed at most. */
        const val MAX_HITS = 500

        private const val TAG = "MqttViewModel"
    }
}

internal const val SYS = "\$SYS"
