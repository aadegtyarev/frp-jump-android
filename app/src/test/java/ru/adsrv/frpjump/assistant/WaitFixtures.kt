package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.adsrv.frpjump.settings.AssistantLimits
import java.util.concurrent.ConcurrentHashMap

/**
 * Wait sources in memory: a tab or tunnel exists once [tabs]/[tunnels] names it; a wait for it ends when the test completes the matching deferred
 * with [fire] or [gone]. Nothing real is watched.
 */
internal class FakeWaitSources : WaitSources {
    val tabs = ConcurrentHashMap<Int, String>()
    val tunnels = ConcurrentHashMap<Pair<String, String>, String>()
    private val results = ConcurrentHashMap<WaitSpec, CompletableDeferred<SourceResult>>()
    val awaited = java.util.concurrent.CopyOnWriteArrayList<WaitSpec>()

    override fun subject(spec: WaitSpec): String? = when {
        spec.kind.isTab -> tabs[spec.tabId]
        spec.kind.isTunnel -> tunnels[(spec.profileId ?: "") to (spec.connectionId ?: "")]
        else -> null
    }

    override suspend fun await(spec: WaitSpec): SourceResult {
        awaited += spec
        return results.getOrPut(spec) { CompletableDeferred() }.await()
    }

    fun fire(spec: WaitSpec) { results.getOrPut(spec) { CompletableDeferred() }.complete(SourceResult.FIRED) }
    fun gone(spec: WaitSpec) { results.getOrPut(spec) { CompletableDeferred() }.complete(SourceResult.GONE) }
}

/** A registry over [FakeWaitSources] whose clock and sleep the test controls: [timeUp] ends the sleep of every wait; delivered events are recorded. */
internal class WaitFixture(var limits: AssistantLimits = AssistantLimits(), val sources: FakeWaitSources = FakeWaitSources()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val delivered = java.util.concurrent.CopyOnWriteArrayList<List<WaitEvent>>()
    val sleepGate = CompletableDeferred<Unit>()
    var now = 1_000_000_000L
    val waits = AssistantWaits(scope, sources, { limits }, wallClock = { now }, sleep = { sleepGate.await() }, deliver = { delivered += it }, tickMs = 1_000L)
    /** The wall clock jumps past every wait's end and the sleeps return. */
    fun timeUp() { now += 24L * 3_600_000L; sleepGate.complete(Unit) }
    fun close() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
}
