package ru.adsrv.frpjump.ops.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.TunnelRefusal
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.TunnelDisconnects
import ru.adsrv.frpjump.undo.UndoKind

/** Disconnect, moved out of the Connections actions: dark at once, told when the notice goes, Undo restores. */
class TunnelDownTest {
    private var timer: (() -> Unit)? = null
    private var counts: Map<UndoKind, Int>? = null
    private var undoOnNotice: (() -> Unit)? = null
    private val stopped = mutableListOf<TunnelRef>()
    private val pending = PendingUndo(
        timer = { _, block -> timer = block; val cancel: () -> Unit = { if (timer === block) timer = null }; cancel },
        run = { commit -> runBlocking { commit() } },
        announce = { c, undo -> counts = c; undoOnNotice = undo.takeIf { c != null } },
    )
    private val ssh = TunnelRef("p1", "dev-a", 22)
    private val web = TunnelRef("p1", "dev-a", 80)
    private val other = TunnelRef("p1", "dev-b", 22)
    private val elsewhere = TunnelRef("p2", "dev-a", 22)
    private val up = mutableSetOf(ssh, web, other, elsewhere)
    private val disconnects = TunnelDisconnects(pending, isUp = { it in up }) { stopped += it }

    private class Host(val up: Map<String, List<TunnelRef>>, val restoring: Set<String> = emptySet()) : TunnelDownHost {
        val told = mutableListOf<String>()
        var tellAllGate: CompletableDeferred<Unit>? = null
        override fun profiles() = up.keys
        override fun up(profile: String) = up[profile].orEmpty()
        override fun restoring() = restoring
        override fun tellAsync(profile: String) { told += "async $profile" }
        override suspend fun tellAll() { told += "all-start"; tellAllGate?.await(); told += "all-end" }
    }

    private val full = Host(mapOf("p1" to listOf(ssh, web, other), "p2" to listOf(elsewhere)))
    private val nothing = Host(mapOf("p1" to emptyList(), "p2" to emptyList()))
    private fun op(host: Host = full) = TunnelDown(disconnects, host)
    private fun dark() = PendingUndo.darkTunnels(pending.hidden.value)
    private fun expire() = checkNotNull(timer) { "no timer running" }.invoke()

    /** As the screens start it: undispatched, so what is done before the first suspension is done when this returns. */
    private fun kotlinx.coroutines.CoroutineScope.undispatched(target: TunnelDownTarget, host: Host = full, result: (OpResult<Unit>) -> Unit = {}) =
        launch(start = CoroutineStart.UNDISPATCHED) { result(op(host)(target)) }

    @Test fun `declares its name and that it changes only this device now`() {
        assertEquals("tunnel_down", op().name)
        assertEquals(Effect.CHANGE_LOCAL, op().effect)
        assertNull(op().target(TunnelDownTarget.All))
    }

    @Test fun `a row goes dark before the operation first suspends and the server is told only when the notice goes`() = runBlocking {
        var result: OpResult<Unit>? = null
        val job = undispatched(TunnelDownTarget.One(ssh)) { result = it }
        // No yield since launch: the effect is immediate.
        assertEquals(setOf("dev-a:22"), PendingUndo.darkTunnels(pending.hidden.value, "p1"))
        assertEquals(mapOf(UndoKind.DISCONNECTED to 1), counts)
        assertEquals(OpResult.Done(Unit), result)
        assertTrue(stopped.isEmpty())
        job.join()
        expire()
        assertEquals(listOf(ssh), stopped)
    }

    @Test fun `Undo restores it and nothing reaches the server`() = runBlocking {
        op()(TunnelDownTarget.One(ssh))
        undoOnNotice!!.invoke()
        assertEquals(emptySet<String>(), pending.hidden.value)
        pending.commitAll()
        assertTrue(stopped.isEmpty())
    }

    @Test fun `closing the app completes the disconnect`() = runBlocking {
        op()(TunnelDownTarget.One(ssh))
        pending.commitAll()
        assertEquals(listOf(ssh), stopped)
    }

    @Test fun `a device takes its tunnels in its profile only, in order, and not those dark already`() = runBlocking {
        disconnects.disconnect(listOf(web))
        var result: OpResult<Unit>? = null
        val job = undispatched(TunnelDownTarget.Device("p1", "dev-a")) { result = it }
        // No yield since launch: dark and done at once.
        assertEquals(setOf("dev-a:22", "dev-a:80"), PendingUndo.darkTunnels(pending.hidden.value, "p1"))
        assertTrue(PendingUndo.darkTunnels(pending.hidden.value, "p2").isEmpty())
        assertEquals(OpResult.Done(Unit), result)
        job.join()
        expire()
        // Each commit runs after the one before: first the already waiting one.
        assertEquals(listOf(web, ssh), stopped)
    }

    @Test fun `a profile takes all its tunnels under one notice`() = runBlocking {
        var result: OpResult<Unit>? = null
        val job = undispatched(TunnelDownTarget.Profile("p1")) { result = it }
        // No yield since launch: dark and done at once.
        assertEquals(mapOf(UndoKind.DISCONNECTED to 3), counts)
        assertEquals(setOf("dev-a:22", "dev-a:80", "dev-b:22"), PendingUndo.darkTunnels(pending.hidden.value, "p1"))
        assertTrue(PendingUndo.darkTunnels(pending.hidden.value, "p2").isEmpty())
        assertEquals(OpResult.Done(Unit), result)
        job.join()
        expire()
        assertEquals(listOf(ssh, web, other), stopped)
    }

    @Test fun `a forgotten profile is refused for a profile and for a device, and nothing is queued`() = runBlocking {
        assertEquals(OpResult.Refused(TunnelRefusal.ProfileGone), op()(TunnelDownTarget.Profile("gone")))
        assertEquals(OpResult.Refused(TunnelRefusal.ProfileGone), op()(TunnelDownTarget.Device("gone", "dev-a")))
        assertTrue(pending.hidden.value.isEmpty())
        assertNull(counts)
    }

    @Test fun `a profile with nothing up is done with nothing queued`() = runBlocking {
        assertEquals(OpResult.Done(Unit), op(nothing)(TunnelDownTarget.Profile("p1")))
        assertTrue(pending.hidden.value.isEmpty())
    }

    @Test fun `disconnect all with tunnels up darkens every one at once, tells restoring profiles now and does not wait`() = runBlocking {
        val host = Host(full.up, restoring = setOf("p2", "gone"))
        var result: OpResult<Unit>? = null
        val job = undispatched(TunnelDownTarget.All, host) { result = it }
        // No yield since launch: everything is dark, restoring profiles are told and the result is in.
        assertEquals(mapOf(UndoKind.DISCONNECTED to 4), counts)
        assertEquals(setOf("dev-a:22", "dev-a:80", "dev-b:22"), PendingUndo.darkTunnels(pending.hidden.value, "p1"))
        assertEquals(setOf("dev-a:22"), PendingUndo.darkTunnels(pending.hidden.value, "p2"))
        assertEquals(OpResult.Done(Unit), result)
        // Restoring profiles are told now (asynchronously); the others when the notice goes; tellAll is not used.
        assertEquals(listOf("async p2"), host.told)
        assertTrue(stopped.isEmpty())
        job.join()
        expire()
        assertEquals(listOf(ssh, web, other, elsewhere), stopped)
    }

    @Test fun `disconnect all with nothing up tells every server and is done only after that`() = runBlocking {
        val host = Host(nothing.up)
        host.tellAllGate = CompletableDeferred()
        var result: OpResult<Unit>? = null
        val job = undispatched(TunnelDownTarget.All, host) { result = it }
        assertEquals(listOf("all-start"), host.told)
        assertNull("the spinner waits for the servers", result)
        assertFalse(job.isCompleted)
        host.tellAllGate!!.complete(Unit)
        job.join()
        assertEquals(listOf("all-start", "all-end"), host.told)
        assertEquals(OpResult.Done(Unit), result)
        assertTrue(pending.hidden.value.isEmpty())
    }

    @Test fun `disconnect all with every tunnel already dark counts as nothing up`() = runBlocking {
        disconnects.disconnect(listOf(ssh, web, other, elsewhere))
        val host = Host(full.up)
        op(host)(TunnelDownTarget.All)
        assertEquals(listOf("all-start", "all-end"), host.told)
    }

    @Test fun `a tunnel used again while its disconnect waits is revived and the commit then does nothing`() = runBlocking {
        op()(TunnelDownTarget.One(ssh))
        assertTrue(disconnects.revive(ssh))
        pending.commitAll()
        assertTrue(stopped.isEmpty())
    }

    @Test fun `the Connections actions keep no disconnect or direct logic of their own`() {
        val source = java.io.File("src/main/java/ru/adsrv/frpjump/ui/connections/ConnectionActions.kt").readText()
        assertFalse(source.contains("disconnects.disconnect("))
        assertFalse(source.contains("directChoices."))
        assertFalse(source.contains("disconnects.shown"))
        assertFalse(source.contains("tunnels.connectAsync(c.device, c.port, c.kind)"))
    }

    @Test fun `the Connections adapter starts operations undispatched in the app scope and keeps its notices and spinner order`() {
        val source = java.io.File("src/main/java/ru/adsrv/frpjump/ui/connections/ConnectionActions.kt").readText()
        val asked = source.substringAfter("private fun asked(").substringBefore("\n    }")
        assertTrue(asked.contains("app.appScope.launch(start = CoroutineStart.UNDISPATCHED)"))
        assertTrue(source.contains("TunnelDownTarget.Profile(profile)).say()"))
        assertTrue(source.contains("TunnelDownTarget.Device(profile, device)).say()"))
        assertTrue(source.contains("NoticeKind.WARN, long = true)") && source.contains("(result.reason as DirectRefusal).userText(), NoticeKind.WARN, long = true"))
        assertTrue(Regex("tunnelDown\\(TunnelDownTarget\\.All\\)\\s*onDone\\(\\)").containsMatchIn(source))
    }
}
