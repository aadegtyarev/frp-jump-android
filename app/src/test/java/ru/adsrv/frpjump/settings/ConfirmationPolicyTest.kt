package ru.adsrv.frpjump.settings

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.ssh.MemoryStorage

class ConfirmationPolicyTest {
    private val ssh = AssistantToolId.SSH_COMMAND
    private val key = GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("p", "c"))
    private val ask = ConfirmationPolicy()
    private val off = ConfirmationPolicy(global = ConfirmationMode.OFF)

    @Test fun `the default is ask and resolve uses the global value`() {
        assertEquals(ConfirmationMode.ASK, ask.resolve()); assertEquals(ConfirmationMode.OFF, off.resolve())
        assertEquals(ConfirmationPolicy.VERSION, ask.version)
    }

    @Test fun `overrides of any level win by specificity without changing the call shape`() {
        val device = ConfirmationScope("device", "d1"); val target = ConfirmationScope("target", "p", "c")
        val policy = ConfirmationPolicy(global = ConfirmationMode.OFF, overrides = listOf(
            ConfirmationOverride(device, ConfirmationMode.ASK), ConfirmationOverride(target, ConfirmationMode.OFF)))
        assertEquals(ConfirmationMode.OFF, policy.resolve())
        assertEquals(ConfirmationMode.ASK, policy.resolve(listOf(device)))
        assertEquals(ConfirmationMode.OFF, policy.resolve(listOf(target, device)))
        assertEquals(ConfirmationMode.ASK, policy.resolve(listOf(ConfirmationScope("tunnel", "x"), device)))
        assertEquals(ConfirmationMode.ASK, ConfirmationPolicy(overrides = listOf(ConfirmationOverride(target, ConfirmationMode.ASK))).resolve(listOf(target)))
    }

    private fun grants(world: FakeGrantWorld = FakeGrantWorld()) = SessionGrants(world)

    @Test fun `only changing tools ask and ask means ask unless a grant or the policy waives it`() {
        val grants = grants()
        assertTrue(mustAsk(ssh, key, ask, grants))
        for (read in AssistantToolId.entries.filter { !it.effect.changes }) assertFalse(read.name, mustAsk(read, GrantKey(read.actionClass, GrantTarget("p", "c")), ask, grants))
        assertFalse(mustAsk(null, null, ask, grants))
        // A changing tool that is catalogued as not confirming never asks, whatever its class.
        for (quiet in AssistantToolId.entries.filter { it.effect.changes && !it.confirms }) assertFalse(quiet.name, mustAsk(quiet, GrantKey(quiet.actionClass, GrantTarget("p", "c")), ask, grants))
        assertFalse(mustAsk(ssh, key, off, grants))
        assertTrue(grants.record(key, ssh, grants.stamp(key.target)!!))
        assertFalse(mustAsk(ssh, key, ask, grants))
        assertTrue("another connection still asks", mustAsk(ssh, GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("p", "other")), ask, grants))
        assertTrue("another profile still asks", mustAsk(ssh, GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("q", "c")), ask, grants))
        assertTrue(mustAsk(ssh, null, ask, grants))
    }

    @Test fun `the decision for every tool x global ask or off x no grant or a valid grant`() {
        for (tool in AssistantToolId.entries) {
            val target = GrantTarget("p", "c"); val k = GrantKey(tool.actionClass, target)
            val none = grants()
            val held = grants().also { g -> g.record(GrantKey(ActionClass.CONNECTION_CHANGE, target), AssistantToolId.SSH_COMMAND, g.stamp(target)!!) }
            val asksToday = tool.effect.changes && tool.confirms
            assertEquals("${tool.name}: no grant, ask", asksToday, mustAsk(tool, k, ask, none))
            assertFalse("${tool.name}: global off", mustAsk(tool, k, off, none)); assertFalse("${tool.name}: global off with a grant", mustAsk(tool, k, off, held))
            // A valid grant of CONNECTION_CHANGE waives only the grantable tools of that class.
            assertEquals("${tool.name}: a valid grant", asksToday && !(tool.grantable), mustAsk(tool, k, ask, held))
        }
    }

    @Test fun `a grant is keyed by class and connection only and the key is structured`() {
        assertEquals(key, GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("p", "c")))
        assertNotEquals(key, GrantKey(ActionClass.APP_CHANGE, GrantTarget("p", "c")))
        assertNotEquals("structured, not joined", GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("a/b", "c")), GrantKey(ActionClass.CONNECTION_CHANGE, GrantTarget("a", "b/c")))
    }

    @Test fun `fixed model texts are exact`() {
        assertEquals("The owner declined this call. Nothing was executed. Do not repeat it or do the same thing another way unless the owner asks.", ConfirmationTexts.DECLINED)
        assertEquals("No answer from the owner within 5 minutes. Nothing was executed.", ConfirmationTexts.NO_ANSWER)
        assertEquals(300_000L, ConfirmationTexts.WAIT_LIMIT_MS)
    }

    @Test fun `the persisted shape is a versioned object with the global value`() {
        assertEquals("""{"version":1,"global":"off"}""", WireJson.encodeToString(off))
        assertEquals("""{"version":1,"global":"ask"}""", WireJson.encodeToString(ask))
        val file = MemoryStorage(); val written = SettingsStore(file)
        written.setAssistantConfirmations(ConfirmationMode.OFF)
        assertTrue(file.text!!.contains(""""assistant_confirmations":{"version":1,"global":"off"}"""))
    }
}
