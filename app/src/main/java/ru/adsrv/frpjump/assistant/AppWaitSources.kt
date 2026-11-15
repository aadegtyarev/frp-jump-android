package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds

/**
 * What the app's waits watch: the owner's open terminal tabs (through [AssistantTerminalTabs]) and saved tunnels (through [AssistantTunnels]),
 * by looking at app state every [pollMs] and, for a tab's text, at the tab's new output as it arrives. Reads state only; nothing is typed or started.
 */
internal class AppWaitSources(
    private val tabs: AssistantTerminalTabs?,
    private val tunnels: AssistantTunnels?,
    private val pollMs: Long = POLL_MS,
    private val nanos: () -> Long = System::nanoTime,
    private val quietMs: Long = QUIET_MS,
) : WaitSources {
    private val quietNanos get() = quietMs * NANOS_PER_MS
    override fun subject(spec: WaitSpec): String? = when {
        spec.kind.isTab -> spec.tabId?.let { id -> tabs?.handle(id)?.let(tabs::words) }
        spec.kind.isTunnel -> ids(spec)?.let { tunnels?.find(it)?.name }
        else -> null
    }

    private fun ids(spec: WaitSpec) = if (spec.profileId != null && spec.connectionId != null) SavedTunnelIds(spec.profileId, spec.connectionId) else null

    override suspend fun await(spec: WaitSpec): SourceResult = when (spec.kind) {
        WaitKind.TAB_CONNECTED -> watchTab(spec) { tab -> tabState(tab.state) == TabState.CONNECTED }
        WaitKind.TAB_CLOSED -> watchTab(spec) { tab -> tabState(tab.state) == TabState.CLOSED }
        WaitKind.TAB_QUIET -> {
            val start = nanos()
            watchTab(spec) { tab -> nanos() - maxOf(start, tab.lastOutputNanos) >= quietNanos }
        }
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

    private suspend fun awaitText(spec: WaitSpec): SourceResult {
        val id = spec.tabId ?: return SourceResult.GONE
        val needle = spec.text ?: return SourceResult.GONE
        val tab = tabs?.handle(id) ?: return SourceResult.GONE
        val found = CompletableDeferred<Unit>()
        val matcher = OutputMatcher(needle)
        // Only output that comes after the wait began: the tap sees nothing earlier.
        val tap = tab.watchOutput { bytes, length -> if (matcher.feed(bytes, length)) found.complete(Unit) } ?: return SourceResult.GONE
        try {
            while (true) {
                if (found.isCompleted) return SourceResult.FIRED
                if (tabs.handle(id) == null) return SourceResult.GONE
                delay(pollMs)
            }
        } finally { tap.close() }
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
        /** The quiet time of `tab_quiet`, for now the same as the wait `terminal_input` gives a tab's output to go quiet. */
        private const val QUIET_MS = AssistantTerminalTabs.MAX_WAIT_MS
        private const val NANOS_PER_MS = 1_000_000L
    }
}
