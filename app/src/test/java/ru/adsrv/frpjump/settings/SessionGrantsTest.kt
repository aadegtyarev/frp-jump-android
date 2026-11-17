package ru.adsrv.frpjump.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ops.ActionClass

private val SSH = AssistantToolId.SSH_COMMAND
private val TYPE = AssistantToolId.TERMINAL_INPUT
private val CONNECTION = ActionClass.CONNECTION_CHANGE

/** The rules of [SessionGrants] and [SessionGrantCleaner] over a fake world: each row of "When a grant ends". */
class SessionGrantsTest {
    private val a = GrantTarget("p", "c1")
    private val b = GrantTarget("p", "c2")
    private val world = FakeGrantWorld()
    private val grants = SessionGrants(world)
    private fun key(t: GrantTarget) = GrantKey(CONNECTION, t)
    private fun give(t: GrantTarget, tool: AssistantToolId = SSH) = grants.record(key(t), tool, grants.stamp(t)!!, grants.coverableBy(tool))

    @Test fun `covers the tools in effect at the answer, on the connection only, and a repeated answer replaces the entry`() {
        world.off += TYPE
        assertTrue(give(a))
        assertTrue(grants.covers(SSH, key(a))); assertFalse("typing was not in effect at the answer", grants.covers(TYPE, key(a)))
        assertFalse(grants.covers(SSH, key(b)))
        world.off.clear()
        assertFalse("switching it on later does not add it", grants.covers(TYPE, key(a)))
        assertTrue("a repeated answer replaces the entry with the tools in effect now", give(a))
        assertTrue(grants.covers(TYPE, key(a))); assertEquals(1, grants.snapshot().size)
        assertEquals(setOf(SSH, TYPE), grants.coverableBy(SSH)); assertEquals(emptySet<AssistantToolId>(), grants.coverableBy(AssistantToolId.TERMINAL_CLOSE))
        world.off += TYPE; assertEquals(setOf(SSH), grants.coverableBy(TYPE))
    }

    @Test fun `the grant covers no more than the card said even if typing is turned on while the card is open`() {
        world.off += TYPE
        val offered = grants.coverableBy(SSH); assertEquals(setOf(SSH), offered)
        val stamp = grants.stamp(a)!!
        world.off.clear()
        assertTrue(grants.record(key(a), SSH, stamp, offered))
        assertTrue(grants.covers(SSH, key(a))); assertFalse("typing was not on the card", grants.covers(TYPE, key(a)))
        // And the asked tool must be among what the card offered.
        assertFalse(grants.record(key(b), TYPE, grants.stamp(b)!!, setOf(SSH)))
    }

    @Test fun `a tool switched off and on again, even before any sweep, is not covered and not recorded`() {
        give(a)
        world.switchOff(SSH); world.off.clear()
        assertFalse("off count grew", grants.covers(SSH, key(a))); assertTrue(grants.covers(TYPE, key(a)))
        // During the question: switched off and on again, the stamp was taken before.
        val offered = grants.coverableBy(TYPE); val stamp = grants.stamp(b)!!
        world.switchOff(TYPE); world.off.clear()
        assertFalse("not recorded: the tool was switched off during the question", grants.record(key(b), TYPE, stamp, offered))
        assertTrue(grants.snapshot().none { it.target == b })
    }

    @Test fun `a locked app gives no stamp and covers nothing, read at every use`() {
        give(a)
        world.locked = true
        assertNull(grants.stamp(b)); assertFalse(grants.covers(SSH, key(a))); assertTrue("and the entry is dropped", grants.snapshot().isEmpty())
        world.locked = false
        assertFalse("unlocking brings nothing back", grants.covers(SSH, key(a)))
        assertNotNull(grants.stamp(b))
    }

    @Test fun `no stamp without an open period a connection or the assistant on, and nothing is recorded for a tool out of effect`() {
        world.period = null; assertNull(grants.stamp(a))
        world.period = 1; world.fingerprint = null; assertNull(grants.stamp(a))
        world.fingerprint = "f1"; world.enabled = false; assertNull(grants.stamp(a))
        world.enabled = true
        val stamp = grants.stamp(a)!!
        world.off += SSH; assertFalse(grants.record(key(a), SSH, stamp, setOf(SSH)))
        assertTrue(grants.snapshot().isEmpty())
        assertFalse(grants.record(GrantKey(ActionClass.APP_CHANGE, a), AssistantToolId.SKILL_SAVE, stamp, setOf(AssistantToolId.SKILL_SAVE)))
        assertFalse(grants.record(key(a), AssistantToolId.TERMINAL_CLOSE, stamp, setOf(AssistantToolId.TERMINAL_CLOSE)))
    }

    @Test fun `each thing a grant is bound to ends it, lazily at the lookup`() {
        val changes = listOf<(FakeGrantWorld) -> Unit>({ it.period = 2 }, { it.period = null }, { it.fingerprint = "f2" }, { it.fingerprint = null },
            { it.disableVersion = 1 }, { it.enabled = false })
        for (change in changes) {
            val w = FakeGrantWorld(); val g = SessionGrants(w)
            g.record(key(a), SSH, g.stamp(a)!!, g.coverableBy(SSH))
            assertTrue(g.covers(SSH, key(a)))
            change(w)
            assertFalse(g.covers(SSH, key(a))); assertTrue("and the entry is gone", g.snapshot().isEmpty()); assertTrue(g.observe().value.isEmpty())
        }
    }

    @Test fun `the grant of one connection ends without touching another`() {
        give(a); give(b)
        world.fingerprints[a] = "edited"
        assertFalse(grants.covers(SSH, key(a))); assertTrue(grants.covers(SSH, key(b)))
        world.periods[b] = 9
        assertFalse(grants.covers(SSH, key(b)))
    }

    @Test fun `sweep drops stale grants and out of effect tools without a lookup and an emptied grant goes`() {
        give(a); give(b)
        assertEquals(setOf(a, b), grants.observe().value)
        world.off += TYPE; grants.sweep()
        assertEquals("one tool left", setOf(a, b), grants.observe().value); assertTrue(grants.covers(SSH, key(a)))
        world.off.clear(); assertFalse("it does not come back", grants.covers(TYPE, key(a)))
        world.fingerprints[b] = "x"; grants.sweep()
        assertEquals(setOf(a), grants.observe().value)
        world.off += SSH; grants.sweep()
        assertTrue("typing was already gone and ssh now: nothing left", grants.snapshot().isEmpty()); assertTrue(grants.observe().value.isEmpty())
    }

    @Test fun `forget removes every grant of one connection at once and clear all`() {
        give(a); give(b)
        grants.forgetConnection("p", "c1")
        assertEquals(setOf(b), grants.observe().value); assertFalse(grants.covers(SSH, key(a)))
        grants.forgetConnection("nobody", "x"); assertEquals(1, grants.snapshot().size)
        grants.clear(); assertTrue(grants.snapshot().isEmpty()); assertTrue(grants.observe().value.isEmpty())
    }

    @Test fun `grants are safe under concurrent use`() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { n -> pool.submit {
                repeat(300) { i -> val t = GrantTarget("p$n", "c$i"); grants.record(key(t), SSH, grants.stamp(t)!!, grants.coverableBy(SSH)); grants.covers(SSH, key(a)); grants.snapshot(); grants.sweep() }
            } }
            futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(1200, grants.snapshot().size)
        grants.forgetConnection("p1", "c5"); assertEquals(1199, grants.snapshot().size)
    }

    @Test fun `the cleaner works with no conversation, on every change and on the app lock`() {
        val changes = MutableStateFlow(0); val locked = MutableStateFlow(false)
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            SessionGrantCleaner(grants, scope, changes, locked)
            give(a); give(b)
            // A tunnel closed (its period gone): the sweep follows the change, with no lookup.
            world.periods[a] = 7; changes.value++
            assertEquals(setOf(b), grants.observe().value)
            world.off += SSH; changes.value++
            assertEquals("ssh_command left, typing still covers", setOf(b), grants.observe().value)
            world.off += TYPE; changes.value++
            assertTrue("nothing left: the grant is dropped", grants.snapshot().isEmpty())
            // The app lock ends everything, a grant given just before it included.
            world.off.clear(); give(a); give(b)
            locked.value = true
            assertTrue("the lock ends all grants", grants.snapshot().isEmpty()); assertTrue(grants.observe().value.isEmpty())
            assertFalse("and a covered call then asks", grants.covers(SSH, key(a)))
            locked.value = false
            assertTrue("unlocking brings nothing back", grants.snapshot().isEmpty())
        } finally { scope.cancel() }
    }
}
