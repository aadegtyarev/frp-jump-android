package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.delay
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

    /** The tab's output source again when it is time: the first time, after new output has settled, or when [rereadMs] passed since the last look at new output. */
    private inner class Looks(private val id: Int) {
        private var seen = Long.MIN_VALUE
        private var lastAt = Long.MIN_VALUE

        /** The tab's [TabText], or null when it is not time or the tab cannot be read now (not connected, a failed read: the next look tries again). [TabNotOpen] when it is gone. */
        suspend fun next(tab: TabHandle): TabText? {
            val now = nanos()
            val output = tab.lastOutputNanos
            val first = seen == Long.MIN_VALUE
            val due = first || (output != seen && (now - output >= settleMs * NANOS_PER_MS || now - lastAt >= rereadMs * NANOS_PER_MS))
            if (!due) return null
            lastAt = now
            val text = try { tabs?.snapshot(id)?.second }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (gone: TabNotOpen) { throw gone }
            catch (_: Exception) { null }
            // What was not read is looked at again, not counted as seen.
            if (text != null) seen = output
            return text
        }
    }

    /**
     * `tab_text`: the text is looked for in the rows after the cursor, from the wait's `from` or, without one, the end of the output when the wait began
     * (the first look). Rows before a `from` that are no longer kept are not told apart: everything the source still holds is looked at.
     */
    private suspend fun awaitText(spec: WaitSpec): SourceResult {
        val id = spec.tabId ?: return SourceResult.GONE
        val needle = spec.text ?: return SourceResult.GONE
        var cursor = spec.from?.let(TabCursor::parse)
        val looks = Looks(id)
        try {
            while (true) {
                val tab = tabs?.handle(id) ?: return SourceResult.GONE
                val text = looks.next(tab)
                if (text != null) {
                    val start = cursor
                    if (start == null) cursor = text.cursor else if ((text.since(start) ?: text.all()).contains(needle)) return SourceResult.FIRED
                }
                delay(pollMs)
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
        val looks = Looks(id)
        var printed = false
        try {
            while (true) {
                val tab = tabs?.handle(id) ?: return SourceResult.GONE
                if (from != null && !printed) printed = looks.next(tab)?.let { (it.since(from) ?: it.all()).isNotEmpty() } ?: false
                val last = if (printed) tab.lastOutputNanos else maxOf(start, tab.lastOutputNanos)
                if (nanos() - last >= quietNanos) return SourceResult.FIRED
                delay(pollMs)
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
