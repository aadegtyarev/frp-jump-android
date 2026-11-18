package ru.adsrv.frpjump.settings

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage

/** [ConnectionGrants]: the persistent "For this connection" permission over the real settings store and a world the test steers. */
class ConnectionGrantsTest {
    private val target = GrantTarget("p", "c")
    private val key = GrantKey(ActionClass.CONNECTION_CHANGE, target)
    private val ssh = AssistantToolId.SSH_COMMAND
    private val typing = AssistantToolId.TERMINAL_INPUT
    private val both = setOf(ssh, typing)

    /** A storage that can be made to fail on write. */
    private class Flaky(var data: String? = null, var failWrites: Boolean = false) : TextStorage {
        override fun read() = data
        override fun write(text: String) { if (failWrites) throw java.io.IOException("Synthetic"); data = text }
        override fun delete() { data = null }
    }

    private class Rig(val storage: TextStorage = MemoryStorage()) {
        val settings = SettingsStore(storage).apply { update { it.copy(assistantEnabled = true, assistantSshCommand = true, assistantTerminalTabs = true, assistantTerminalInput = true, assistantSessions = true) } }
        /** Steered on its own: the switches in the store are real, what the world says may differ (as it can between a read and a write). */
        val fake = FakeGrantWorld()
        /** Over the real store: the switches and their counts are the store's own. */
        val real = SettingsGrantWorld(settings)
        var world: GrantWorld = fake
        val grants get() = ConnectionGrants(settings, world, now = { 1_763_020_800_000L })
        fun stamp(grants: ConnectionGrants = this.grants, t: GrantTarget = GrantTarget("p", "c")) = grants.stamp(t)!!
        fun give(tool: AssistantToolId = AssistantToolId.SSH_COMMAND, offered: Set<AssistantToolId> = setOf(AssistantToolId.SSH_COMMAND, AssistantToolId.TERMINAL_INPUT)): RecordResult =
            runBlocking { val g = grants; g.record(GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("p", "c")), tool, g.stamp(GrantTarget("p", "c"))!!, offered) }
        val records get() = settings.assistantConfirmations.overrides
    }

    @Test fun `a recorded answer covers exactly its tools on its connection and nothing else`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        assertTrue(r.grants.covers(ssh, key)); assertTrue(r.grants.covers(typing, key))
        assertFalse("another connection", r.grants.covers(ssh, GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("p", "other"))))
        assertFalse("another profile", r.grants.covers(ssh, GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("q", "c"))))
        assertFalse("another class", r.grants.covers(ssh, GrantKey(ActionClass.APP_CHANGE, target)))
        assertEquals(1_763_020_800_000L, r.records.single().at); assertEquals(listOf("ssh_command", "terminal_input"), r.records.single().tools)
    }

    @Test fun `a tool that is not grantable is never covered and never recorded whatever a record says`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        for (tool in AssistantToolId.entries.filter { !it.grantable }) {
            assertFalse(tool.name, r.grants.covers(tool, key)); assertFalse(tool.name, r.grants.covers(tool, GrantKey(tool.actionClass, target)))
            assertEquals(tool.name, RecordResult.NOT_RECORDED, runBlocking { r.grants.record(key, tool, r.stamp(), setOf(tool, ssh)) })
        }
        // Even a record somebody wrote by hand that names them (or a read class) covers nothing.
        r.settings.update { it.copy(assistantConfirmations = it.assistantConfirmations.withRecord(r.records.single().copy(tools = listOf("terminal_close", "stop_tunnel", "skill_save", "ssh_command")))) }
        for (tool in listOf(AssistantToolId.TERMINAL_CLOSE, AssistantToolId.STOP_TUNNEL, AssistantToolId.SKILL_SAVE, AssistantToolId.SKILL_DELETE)) assertFalse(tool.name, r.grants.covers(tool, key))
        assertTrue(r.grants.covers(ssh, key))
        // A class that is not grantable or has no question is never recorded.
        assertEquals(RecordResult.NOT_RECORDED, runBlocking { r.grants.record(GrantKey(ActionClass.APP_CHANGE, target), ssh, r.stamp(), both) })
    }

    @Test fun `the record covers the tools the card offered and are in effect, never more`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give(offered = setOf(ssh)))
        assertTrue(r.grants.covers(ssh, key)); assertFalse("typing was not on the card: it asks", r.grants.covers(typing, key))
        assertEquals(listOf("ssh_command"), r.records.single().tools)
        val r2 = Rig(); r2.settings.setAssistantTool(typing, false); r2.fake.switchOff(typing)
        assertEquals("a tool that was off at the answer", RecordResult.RECORDED, r2.give(offered = both)); assertEquals(listOf("ssh_command"), r2.records.single().tools)
        val r3 = Rig(); r3.settings.setAssistantTool(ssh, false); r3.fake.switchOff(ssh)
        assertEquals("the answered tool is off: Allow once", RecordResult.NOT_RECORDED, r3.give(tool = ssh, offered = both)); assertTrue(r3.records.isEmpty())
    }

    @Test fun `a tool that joins the class later is not covered and the record stays`() {
        val r = Rig(); r.settings.setAssistantTool(typing, false); r.fake.switchOff(typing)
        assertEquals(RecordResult.RECORDED, r.give(offered = setOf(ssh)))
        r.settings.setAssistantTool(typing, true); r.fake.off -= typing
        assertFalse("it was not on the card", r.grants.covers(typing, key)); assertTrue(r.grants.covers(ssh, key)); assertEquals(1, r.records.size)
    }

    @Test fun `a tool out of effect now is not covered even while the record names it`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        r.fake.off += typing
        assertFalse(r.grants.covers(typing, key)); assertTrue(r.grants.covers(ssh, key))
    }

    @Test fun `the stamp is taken before the question so a change during the wait records nothing`() {
        // An edit of the connection.
        run { val r = Rig(); val g = r.grants; val stamp = g.stamp(target)!!; r.fake.persistentFp = PersistentFp.Known("v1:pf2")
            assertEquals(RecordResult.NOT_RECORDED, runBlocking { g.record(key, ssh, stamp, both) }); assertTrue(r.records.isEmpty()) }
        // The tunnel's open period ended.
        run { val r = Rig(); val g = r.grants; val stamp = g.stamp(target)!!; r.fake.period = 2L
            assertEquals(RecordResult.NOT_RECORDED, runBlocking { g.record(key, ssh, stamp, both) }); assertTrue(r.records.isEmpty()) }
        // The assistant was switched off and on again.
        run { val r = Rig(); r.world = r.real; val g = r.grants; val stamp = g.stamp(target)!!
            r.settings.update { it.copy(assistantEnabled = false) }; r.settings.update { it.copy(assistantEnabled = true) }
            assertEquals(RecordResult.NOT_RECORDED, runBlocking { g.record(key, ssh, stamp, both) }); assertTrue(r.records.isEmpty()) }
        // The answered tool was switched off and on again: no record; another tool's switching leaves it out of the record.
        run { val r = Rig(); r.world = r.real; val g = r.grants; val stamp = g.stamp(target)!!
            r.settings.setAssistantTool(ssh, false); r.settings.setAssistantTool(ssh, true)
            assertEquals(RecordResult.NOT_RECORDED, runBlocking { g.record(key, ssh, stamp, both) }); assertTrue(r.records.isEmpty()) }
        run { val r = Rig(); r.world = r.real; val g = r.grants; val stamp = g.stamp(target)!!
            r.settings.setAssistantTool(typing, false); r.settings.setAssistantTool(typing, true)
            assertEquals(RecordResult.RECORDED, runBlocking { g.record(key, ssh, stamp, both) }); assertEquals("typing was switched off since the stamp", listOf("ssh_command"), r.records.single().tools) }
        // The fingerprint cannot be told when the stamp is taken: no stamp.
        run { val r = Rig(); r.fake.persistentFp = PersistentFp.Unknown; assertNull(r.grants.stamp(target)) }
    }

    @Test fun `no stamp and no record while the app is locked`() {
        val r = Rig(); val g = r.grants; val stamp = g.stamp(target)!!
        r.fake.locked = true
        assertNull(g.stamp(target)); assertEquals(RecordResult.NOT_RECORDED, runBlocking { g.record(key, ssh, stamp, both) }); assertTrue(r.records.isEmpty())
    }

    @Test fun `a failed save records nothing and leaves the committed value`() {
        val storage = Flaky(); val r = Rig(storage)
        storage.failWrites = true
        assertEquals(RecordResult.SAVE_FAILED, r.give()); assertTrue(r.records.isEmpty()); assertFalse(r.grants.covers(ssh, key))
        storage.failWrites = false
        assertTrue("nothing was kept in memory instead", SettingsStore(storage).assistantConfirmations.overrides.isEmpty())
        assertEquals(RecordResult.RECORDED, r.give())
    }

    @Test fun `it survives a restart a tunnel close and a global switch off and on, and a repeated answer replaces it`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give(offered = setOf(ssh)))
        val again = SettingsStore(r.storage)
        assertTrue("restart", ConnectionGrants(again, r.fake).covers(ssh, key))
        r.fake.period = null; assertTrue("a tunnel disconnect is not tied to it", r.grants.covers(ssh, key))
        r.settings.setAssistantConfirmations(ConfirmationMode.OFF); assertEquals("the global switch makes and ends nothing", 1, r.records.size)
        r.settings.setAssistantConfirmations(ConfirmationMode.ASK); assertTrue(r.grants.covers(ssh, key))
        r.fake.period = 1L; r.settings.update { it.copy(direct = false) }; assertEquals("other updates keep records", 1, r.records.size)
        assertEquals(RecordResult.RECORDED, r.give()); assertEquals("replaced, not added", 1, r.records.size); assertEquals(listOf("ssh_command", "terminal_input"), r.records.single().tools)
    }

    @Test fun `a changed connection or a gone one deletes the record and an unknown one asks and keeps it`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        r.fake.persistentFp = PersistentFp.Unknown
        assertFalse("unknown asks", r.grants.covers(ssh, key)); assertEquals("and keeps it", 1, r.records.size)
        r.grants.sweep(); assertEquals("the cleaner keeps it too", 1, r.records.size)
        r.fake.persistentFp = PersistentFp.Known("v1:pf1"); assertTrue("known again: it covers again", r.grants.covers(ssh, key))
        val g = r.grants; r.fake.persistentFp = PersistentFp.Known("v1:pf2")
        assertFalse("mismatch", g.covers(ssh, key)); assertEquals("nothing is written in a call: the cleaner deletes it", 1, r.records.size); assertTrue("but it is not shown any more", g.currentRecords().isEmpty())
        r.fake.persistentFp = PersistentFp.Known("v1:pf1"); assertFalse("and it does not come back", g.covers(ssh, key))
        g.sweep(); assertTrue("the cleaner deleted it", r.records.isEmpty())
        assertEquals(RecordResult.RECORDED, r.give()); r.fake.persistentFp = PersistentFp.Gone
        val gone = r.grants; assertFalse("gone", gone.covers(ssh, key)); assertEquals(1, r.records.size); gone.sweep(); assertTrue(r.records.isEmpty())
    }

    @Test fun `Forget stops the use at once, before and without the write, and a failed write keeps it revoked until the delete is retried`() {
        val storage = Flaky(); val r = Rig(storage); assertEquals(RecordResult.RECORDED, r.give())
        val g = r.grants
        assertTrue(g.covers(ssh, key))
        // The tap: in memory only. No write has happened and the record is still committed.
        g.revoke("p", "c")
        assertFalse("revoked before any write", g.covers(ssh, key)); assertFalse(g.covers(typing, key)); assertEquals("the record is still committed", 1, r.records.size)
        assertTrue("and its line is gone", g.currentRecords().isEmpty()); assertTrue(runBlocking { g.observeRecords().first() }.isEmpty())
        // The write fails: it throws, the record stays committed, the use stays revoked.
        storage.failWrites = true
        assertThrows(java.io.IOException::class.java) { g.forget("p", "c") }
        assertEquals(1, r.records.size); assertFalse("a failed write never lets it allow calls again", g.covers(ssh, key))
        g.sweep(); assertFalse("a failed retry neither", g.covers(ssh, key)); assertEquals(1, r.records.size)
        // The write works again: the cleaner's sweep deletes it and only then is the revocation cleared.
        storage.failWrites = false; g.sweep()
        assertTrue(r.records.isEmpty()); assertFalse(g.covers(ssh, key))
        // A later answer is a new decision.
        assertEquals(RecordResult.RECORDED, runBlocking { g.record(key, ssh, g.stamp(target)!!, both) }); assertTrue(g.covers(ssh, key))
    }

    @Test fun `an answer that was in flight when the owner forgot cannot write the record back, and Forget profile revokes every connection of the profile`() {
        val r = Rig(); val g = r.grants
        val stamp = g.stamp(target)!!
        g.revoke("p", "c")
        assertEquals(RecordResult.NOT_RECORDED, runBlocking { g.record(key, ssh, stamp, both) }); assertTrue(r.records.isEmpty())
        // Forget of another connection during the wait also refuses the answer (conservative: forgets are rare).
        val fresh = g.stamp(target)!!; g.revoke("p", "other")
        assertEquals(RecordResult.NOT_RECORDED, runBlocking { g.record(key, ssh, fresh, both) })
        // Forget profile.
        val r2 = Rig(); assertEquals(RecordResult.RECORDED, r2.give()); val g2 = r2.grants
        g2.revokeProfile("p"); assertFalse(g2.covers(ssh, key)); assertFalse(g2.covers(ssh, GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("p", "any"))))
        g2.sweep(); assertTrue(r2.records.isEmpty())
        // Forget profile whose write fails: still never used.
        val storage = Flaky(); val r3 = Rig(storage); assertEquals(RecordResult.RECORDED, r3.give()); val g3 = r3.grants
        storage.failWrites = true
        assertThrows(java.io.IOException::class.java) { g3.forgetProfile("p") }
        assertEquals(1, r3.records.size); assertFalse("a failed Forget profile never lets it allow", g3.covers(ssh, key))
    }

    @Test fun `the cleaner deletes a mismatch or a gone connection with no call and keeps an unknown one`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        r.fake.persistentFps[target] = PersistentFp.Unknown; r.grants.sweep(); assertEquals(1, r.records.size)
        r.fake.persistentFps[target] = PersistentFp.Known("v1:pf1"); r.grants.sweep(); assertEquals(1, r.records.size)
        r.fake.persistentFps[target] = PersistentFp.Known("v1:pf2"); r.grants.sweep(); assertTrue("mismatch", r.records.isEmpty())
        assertEquals(RecordResult.RECORDED, r.give()); r.fake.persistentFps[target] = PersistentFp.Gone; r.grants.sweep(); assertTrue("gone", r.records.isEmpty())
        // A failed delete write is retried at the next change; meanwhile the check at use keeps the record unused.
        val storage = Flaky(); val f = Rig(storage); assertEquals(RecordResult.RECORDED, f.give()); f.fake.persistentFp = PersistentFp.Known("v1:pf2")
        storage.failWrites = true; f.grants.sweep(); assertEquals(1, f.records.size); assertFalse("unused", f.grants.covers(ssh, key))
        storage.failWrites = false; f.grants.sweep(); assertTrue(f.records.isEmpty())
    }

    @Test fun `while the lock's interval has run out no grant is used and the record stays and covers again after the unlock`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        r.fake.locked = true
        assertFalse(r.grants.covers(ssh, key)); assertFalse(r.grants.covers(typing, key)); assertEquals("not deleted by the lock", 1, r.records.size)
        r.grants.sweep(); assertEquals(1, r.records.size)
        r.fake.locked = false; assertTrue(r.grants.covers(ssh, key))
    }

    @Test fun `a connection hidden for Undo is not covered and is covered again when Undo cancels it`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        r.fake.hiddenTargets += target; assertFalse(r.grants.covers(ssh, key)); assertEquals(1, r.records.size)
        r.fake.hiddenTargets -= target; assertTrue(r.grants.covers(ssh, key))
    }

    @Test fun `Forget removes the record of one connection and Forget profile all of a profile`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        r.fake.persistentFp = PersistentFp.Known("v1:pf1")
        runBlocking { val g = r.grants; for (c in listOf("c2", "c3")) g.record(GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("p", c)), ssh, g.stamp(GrantTarget("p", c))!!, both)
            g.record(GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("q", "c")), ssh, g.stamp(GrantTarget("q", "c"))!!, both) }
        assertEquals(4, r.records.size)
        r.grants.forget("p", "c"); assertFalse(r.grants.covers(ssh, key)); assertEquals(setOf("c2", "c3", "q/c"), r.records.map { it.scope.id + "/" + it.scope.sub }.map { if (it.startsWith("p/")) it.removePrefix("p/") else it }.toSet())
        r.grants.forgetProfile("p"); assertEquals(listOf("q"), r.records.map { it.scope.id })
        r.grants.forget("p", "nothing"); r.grants.forgetProfile("none")
    }

    @Test fun `observeRecords shows the connections with their date and tools and follows each change`() = runBlocking {
        val r = Rig(); assertTrue(r.grants.observeRecords().first().isEmpty())
        assertEquals(RecordResult.RECORDED, r.give())
        val shown = r.grants.observeRecords().first()
        assertEquals(setOf(target), shown.keys); assertEquals(1_763_020_800_000L, shown.getValue(target).at); assertEquals(shown, r.grants.currentRecords())
        r.grants.forget("p", "c"); assertTrue(r.grants.observeRecords().first().isEmpty())
    }

    @Test fun `observeRecords hides a revoked record at once, before the write, and names exactly the recorded tools`() = runBlocking {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give(tool = typing, offered = setOf(typing)))
        val g = r.grants
        assertEquals("typing only", setOf(typing), g.observeRecords().first().getValue(target).tools)
        g.revoke("p", "c")
        assertEquals("still committed", 1, r.records.size)
        assertTrue("but not shown", g.observeRecords().first().isEmpty()); assertTrue(g.currentRecords().isEmpty())
        Unit
    }

    @Test fun `a record of both tools says both`() = runBlocking {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give(offered = setOf(ssh, typing)))
        assertEquals(setOf(ssh, typing), r.grants.observeRecords().first().getValue(target).tools)
        Unit
    }

    @Test fun `a tool's switch-off leaves the record for good in the same write and switching on does not bring it back`() {
        val r = Rig(); r.world = r.real; assertEquals(RecordResult.RECORDED, r.give())
        r.settings.setAssistantTool(ssh, false)
        assertEquals("narrowed in the write that switched it off", listOf("terminal_input"), r.records.single().tools)
        r.settings.setAssistantTool(ssh, true)
        assertEquals("not revived", listOf("terminal_input"), r.records.single().tools); assertFalse(r.grants.covers(ssh, key)); assertTrue(r.grants.covers(typing, key))
        assertEquals("not revived after a restart either", listOf("terminal_input"), SettingsStore(r.storage).assistantConfirmations.overrides.single().tools)
        r.settings.setAssistantTool(typing, false); assertTrue("an emptied record is removed", r.records.isEmpty()); r.settings.setAssistantTool(typing, true); assertTrue(r.records.isEmpty())
    }

    @Test fun `Terminal tabs off takes typing out of every record with it`() {
        val r = Rig(); r.world = r.real; assertEquals(RecordResult.RECORDED, r.give())
        r.settings.setAssistantTool(AssistantToolId.TERMINAL_TABS, false)
        assertEquals(listOf("ssh_command"), r.records.single().tools); r.settings.setAssistantTool(AssistantToolId.TERMINAL_TABS, true)
        assertEquals("tabs on again does not bring typing back", listOf("ssh_command"), r.records.single().tools); assertFalse(r.grants.covers(typing, key))
    }

    @Test fun `the assistant off removes every record, on again brings nothing back, also after a restart`() {
        val r = Rig(); r.world = r.real; assertEquals(RecordResult.RECORDED, r.give())
        r.settings.update { it.copy(assistantEnabled = false) }; assertTrue(r.records.isEmpty())
        r.settings.update { it.copy(assistantEnabled = true) }; assertTrue(r.records.isEmpty()); assertFalse(r.grants.covers(ssh, key))
        assertTrue(SettingsStore(r.storage).assistantConfirmations.overrides.isEmpty())
    }

    @Test fun `the switches are narrowed even when one write changes the switch and something else`() {
        val r = Rig(); r.world = r.real; assertEquals(RecordResult.RECORDED, r.give())
        r.settings.update { it.copy(direct = false, assistantSshCommand = false) }; assertEquals(listOf("terminal_input"), r.records.single().tools)
    }

    @Test fun `a failed write of a switch keeps the committed record and switch`() {
        val storage = Flaky(); val r = Rig(storage); r.world = r.real; assertEquals(RecordResult.RECORDED, r.give())
        storage.failWrites = true
        assertThrows(java.io.IOException::class.java) { r.settings.setAssistantTool(ssh, false) }
        assertEquals(both.map { it.wire }.sorted(), r.records.single().tools); assertTrue(r.settings.assistantAvailability.tools[ssh])
    }

    @Test fun `the order is the switch off then a session grant then a persistent one then ask, with the reason`() {
        val r = Rig(); val session = SessionGrants(r.fake)
        val ask = ConfirmationPolicy(); val off = ConfirmationPolicy(global = ConfirmationMode.OFF)
        fun reason(policy: ConfirmationPolicy) = noQuestion(ssh, key, policy, session, r.grants)
        assertNull(reason(ask)); assertEquals(NoQuestion.OFF, reason(off))
        assertEquals(RecordResult.RECORDED, r.give()); assertEquals(NoQuestion.CONNECTION, reason(ask)); assertEquals("the switch is read first", NoQuestion.OFF, reason(off))
        assertTrue(session.record(key, ssh, session.stamp(target)!!, session.coverableBy(ssh)))
        assertEquals("a session grant is read before the persistent one", NoQuestion.SESSION, reason(ask)); assertEquals(NoQuestion.OFF, reason(off))
        assertNull("a tool that does not ask has no reason", noQuestion(AssistantToolId.WEB_SEARCH, null, ask, session, r.grants))
    }

    @Test fun `the decision for every tool x global x session x persistent`() {
        for (tool in AssistantToolId.entries) for (global in ConfirmationMode.entries) for (withSession in listOf(false, true)) for (withPersistent in listOf(false, true)) {
            val r = Rig(); val session = SessionGrants(r.fake); val k = GrantKey(tool.actionClass, target)
            if (withSession) session.record(GrantKey(ActionClass.CONNECTION_CHANGE, target), ssh, session.stamp(target)!!, session.coverableBy(ssh))
            if (withPersistent) r.give()
            val asks = tool.effect.changes && tool.confirms
            val waived = global == ConfirmationMode.OFF || ((withSession || withPersistent) && tool.grantable)
            assertEquals("${tool.name} global=$global session=$withSession persistent=$withPersistent", asks && !waived, mustAsk(tool, k, ConfirmationPolicy(global = global), session, r.grants))
        }
    }

    @Test fun `resolve never lets a record waive the question by itself`() {
        val r = Rig(); assertEquals(RecordResult.RECORDED, r.give())
        assertEquals(ConfirmationMode.ASK, r.settings.assistantConfirmations.resolve())
        assertTrue("without the grants object a record waives nothing", mustAsk(ssh, key, r.settings.assistantConfirmations, SessionGrants(r.fake), null))
    }
}
