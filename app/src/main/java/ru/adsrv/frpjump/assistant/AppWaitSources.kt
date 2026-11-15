package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds

/**
 * What the app's waits watch: the owner's open terminal tabs (through [AssistantTerminalTabs]) and saved tunnels (through [AssistantTunnels]),
 * by looking at app state every [pollMs]. A tab's output is only SIGNALLED by the app's output tap ([TabHandle.lastOutputNanos]); what it says is
 * read from the tab's one output source, the same lines `terminal_read` reads ([TabText]), at most every [rereadMs] and, after new output,
 * once it has been quiet for [settleMs]. Reads state only; nothing is typed or started.
 */
internal class AppWaitSources(
    private val tabs: AssistantTerminalTabs?,
    private val tunnels: AssistantTunnels?,
    private val pollMs: Long = POLL_MS,
    private val nanos: () -> Long = System::nanoTime,
    private val rereadMs: Long = REREAD_MS,
    private val settleMs: Long = SETTLE_MS,
) : WaitSources {
    override fun subject(spec: WaitSpec): String? = when {
        spec.kind.isTab -> spec.tabId?.let { id -> tabs?.handle(id)?.let(tabs::words) }
        spec.kind.isTunnel -> ids(spec)?.let { tunnels?.find(it)?.name }
        else -> null
    }

    private fun ids(spec: WaitSpec) = if (spec.profileId != null && spec.connectionId != null) SavedTunnelIds(spec.profileId, spec.connectionId) else null

    override suspend fun await(spec: WaitSpec): SourceResult = when (spec.kind) {
        WaitKind.TAB_CONNECTED -> watchTab(spec) { tab -> tabState(tab.state) == TabState.CONNECTED }
        WaitKind.TAB_CLOSED -> watchTab(spec) { tab -> tabState(tab.state) == TabState.CLOSED }
        WaitKind.TAB_QUIET -> awaitQuiet(spec)
        WaitKind.TAB_TEXT -> awaitText(spec)
        WaitKind.TUNNEL_ON -> watchTunnel(spec, on = true)
        WaitKind.TUNNEL_OFF -> watchTunnel(spec, on = false)
        WaitKind.TIME -> SourceResult.GONE
    }

    private suspend fun watchTab(spec: WaitSpec, reached: (TabHandle) -> Boolean): SourceResult {
        val id = spec.tabId ?: return SourceResult.GONE
        while (true) {
            val tab = tabs?.handle(id) ?: return SourceResult.GONE
            if (reached(tab)) return SourceResult.FIRED
            delay(pollMs)
        }
    }

    /** One read of a tab's source, kept while the tab has printed nothing since: every wait on the tab slices its own rows out of it. */
    private class Fetched(val stamp: Long, val tab: TerminalTab, val text: TabText)

    private val locks = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.sync.Mutex>()
    private val fetched = java.util.concurrent.ConcurrentHashMap<Int, Fetched>()
    private val followers = java.util.concurrent.ConcurrentHashMap<Int, MutableSet<Follow>>()

    /** What a look at a tab's output found after the wait's read point: the [rows] from it on, and whether anything was [fresh]ly printed besides the cursor row. */
    private class Look(val rows: String, val fresh: Boolean)

    /**
     * A wait's read point in a tab's output: a cursor, the wait's `from` or, without one, the cursor of the first look (the wait's start). Each look reads
     * the rows from the point on from the tab's one source (the same rows `terminal_read` reads, see [TabText]) and moves the point to the end, so the
     * next look fetches only what is new; the row the terminal's cursor is on stays in the next look (a half-printed line is finished there). Output only
     * wakes it: a look is due the first time, and after new output has settled for [settleMs] or at most every [rereadMs] while output goes on. Waits on
     * one tab share a read: it starts from the oldest read point among them, and each wait takes its own rows out of it. A point that cannot be placed
     * any more (its rows were dropped or cleared) is replaced by the cursor of now, and that look matches nothing.
     */
    private inner class Follow(private val id: Int, from: TabCursor?) : AutoCloseable {
        @Volatile var point: TabCursor? = from
        private var seen = Long.MIN_VALUE
        private var lastAt = Long.MIN_VALUE

        init { followers.getOrPut(id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(this) }
        override fun close() { followers[id]?.remove(this) }

        /** The look, or null when it is not time or the tab cannot be read now (not connected, a failed or unsettled read: the next poll tries again). [TabNotOpen] when it is gone. */
        suspend fun next(tab: TabHandle): Look? {
            val now = nanos()
            val output = tab.lastOutputNanos
            val first = seen == Long.MIN_VALUE
            val due = first || (output != seen && (now - output >= settleMs * NANOS_PER_MS || now - lastAt >= rereadMs * NANOS_PER_MS))
            if (!due) return null
            lastAt = now
            val look = try { read(output) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (gone: TabNotOpen) { throw gone }
            catch (_: Exception) { null }
            // What was not read is looked at again, not counted as seen.
            if (look != null) seen = output
            return look
        }

        private suspend fun read(stamp: Long): Look {
            val owner = tabs ?: throw TabNotOpen()
            val from = point
            // The start of a wait without a cursor: nothing is counted before it.
            if (from == null) { point = owner.look(id, ReadMode.Screen(0)).second.cursor; return Look("", false) }
            val since = sliceOf(owner, id, stamp, from)
            if (since != null) {
                point = since.cursor
                return Look(since.rows, owner.offMain { since.cursor.position != from.position || since.rows.lines().drop(1).any { it.isNotBlank() } })
            }
            // The rows from the cursor cannot be told (dropped, cleared): start again from the cursor of now and match nothing on this look. What was
            // printed before it, history included, never fires a wait.
            point = owner.look(id, ReadMode.Screen(0)).second.cursor
            return Look("", true)
        }
    }

    /**
     * The rows of tab [id] from [mine] on. One read of the tab per output (stamp [stamp]), from the oldest read point of the waits on it, sliced for each
     * of them; when [mine] cannot be placed in it, its own read as [AssistantTerminalTabs.rowsSince] does it. Null when it cannot be placed at all.
     */
    private suspend fun sliceOf(owner: AssistantTerminalTabs, id: Int, stamp: Long, mine: TabCursor): AssistantTerminalTabs.Since? =
        locks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }.withLock {
            suspend fun slice(f: Fetched) = owner.offMain { f.text.since(mine) }?.let { AssistantTerminalTabs.Since(f.tab, it, f.text.cursor) }
            fetched[id]?.takeIf { it.stamp == stamp }?.let { cached -> slice(cached)?.let { return@withLock it } }
            val oldest = ((followers[id]?.mapNotNull { it.point } ?: emptyList()) + mine).minByOrNull { it.position } ?: mine
            val (tab, text) = owner.look(id, ReadMode.From(oldest))
            val read = Fetched(stamp, tab, text)
            fetched[id] = read
            slice(read) ?: owner.rowsSince(id, mine)
        }

    /** [needle] in [text], also across the break of a wrapped line (the rows are physical: a long text may be split in two, its space lost). */
    private fun found(text: String, needle: String) = text.contains(needle) || text.replace("\n", "").contains(needle) || text.replace("\n", " ").contains(needle)

    /** `tab_text`: the text is looked for in the rows after the wait's read point (see [Follow]). */
    private suspend fun awaitText(spec: WaitSpec): SourceResult {
        val id = spec.tabId ?: return SourceResult.GONE
        val needle = spec.text ?: return SourceResult.GONE
        val owner = tabs ?: return SourceResult.GONE
        try {
            Follow(id, spec.from?.let(TabCursor::parse)).use { follow ->
                while (true) {
                    val tab = owner.handle(id) ?: return SourceResult.GONE
                    val look = follow.next(tab)
                    if (look != null && owner.offMain { found(look.rows, needle) }) return SourceResult.FIRED
                    delay(pollMs)
                }
            }
        } catch (_: TabNotOpen) { return SourceResult.GONE }
    }

    /**
     * `tab_quiet`: the tab has printed nothing for the wait's seconds, counted from the later of the wait's start and its last output. With a `from`
     * cursor and output after it, counted from that last output alone, so a program that finished before the wait was set is quiet at once.
     */
    private suspend fun awaitQuiet(spec: WaitSpec): SourceResult {
        val id = spec.tabId ?: return SourceResult.GONE
        val quietNanos = (spec.seconds ?: WaitForTool.DEFAULT_QUIET_SECONDS) * 1_000L * NANOS_PER_MS
        val from = spec.from?.let(TabCursor::parse)
        val start = nanos()
        var printed = false
        try {
            Follow(id, from).use { follow ->
                while (true) {
                    val tab = tabs?.handle(id) ?: return SourceResult.GONE
                    if (from != null && !printed) printed = follow.next(tab)?.fresh == true
                    val last = if (printed) tab.lastOutputNanos else maxOf(start, tab.lastOutputNanos)
                    if (nanos() - last >= quietNanos) return SourceResult.FIRED
                    delay(pollMs)
                }
            }
        } catch (_: TabNotOpen) { return SourceResult.GONE }
    }

    private suspend fun watchTunnel(spec: WaitSpec, on: Boolean): SourceResult {
        val ids = ids(spec) ?: return SourceResult.GONE
        val host = tunnels ?: return SourceResult.GONE
        while (true) {
            val tunnel = host.find(ids) ?: return SourceResult.GONE
            if (host.isOn(tunnel) == on) return SourceResult.FIRED
            delay(pollMs)
        }
    }

    companion object {
        /** How often the state a wait watches is looked at. */
        const val POLL_MS = 500L
        /** The most often a tab's output is read again for a wait that watches it. */
        const val REREAD_MS = 1_000L
        /** How long new output must have been quiet before a read is certain to see it all (the terminal shows a chunk a moment after it arrives). */
        const val SETTLE_MS = 250L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
