package ru.adsrv.frpjump.settings

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ssh.MemoryStorage

class ConfirmationPolicyTest {
    private val ssh = AssistantToolId.SSH_COMMAND
    private val key = GrantKey(ssh, "p/c")
    private val ask = ConfirmationPolicy()
    private val off = ConfirmationPolicy(global = ConfirmationMode.OFF)

    @Test fun `the default is ask and resolve uses the global value`() {
        assertEquals(ConfirmationMode.ASK, ask.resolve()); assertEquals(ConfirmationMode.OFF, off.resolve())
        assertEquals(ConfirmationPolicy.VERSION, ask.version)
    }

    @Test fun `overrides of any level win by specificity without changing the call shape`() {
        val device = ConfirmationScope("device", "d1"); val target = ConfirmationScope("target", "p/c")
        val policy = ConfirmationPolicy(global = ConfirmationMode.OFF, overrides = listOf(
            ConfirmationOverride(device, ConfirmationMode.ASK), ConfirmationOverride(target, ConfirmationMode.OFF)))
        assertEquals(ConfirmationMode.OFF, policy.resolve())
        assertEquals(ConfirmationMode.ASK, policy.resolve(listOf(device)))
        assertEquals(ConfirmationMode.OFF, policy.resolve(listOf(target, device)))
        assertEquals(ConfirmationMode.ASK, policy.resolve(listOf(ConfirmationScope("tunnel", "x"), device)))
        assertEquals(ConfirmationMode.ASK, ConfirmationPolicy(overrides = listOf(ConfirmationOverride(target, ConfirmationMode.ASK))).resolve(listOf(target)))
    }

    @Test fun `only changing tools ask and ask means ask unless a grant or the policy waives it`() {
        val grants = GrantStore()
        assertTrue(mustAsk(ssh, key, ask, grants))
        for (read in AssistantToolId.entries.filter { !it.effect.changes }) assertFalse(read.name, mustAsk(read, GrantKey(read, null), ask, grants))
        assertFalse(mustAsk(null, null, ask, grants))
        assertFalse(mustAsk(ssh, key, off, grants))
        grants.grant(key)
        assertFalse(mustAsk(ssh, key, ask, grants))
        assertTrue("another connection still asks", mustAsk(ssh, GrantKey(ssh, "p/other"), ask, grants))
        assertTrue("another profile still asks", mustAsk(ssh, GrantKey(ssh, "q/c"), ask, grants))
        assertTrue(mustAsk(ssh, null, ask, grants))
    }

    @Test fun `grants clear as a whole or per tool and are keyed by tool and target only`() {
        val grants = GrantStore()
        grants.grant(key); grants.grant(GrantKey(AssistantToolId.WEB_SEARCH, null))
        grants.clear(ssh)
        assertFalse(grants.has(key)); assertTrue(grants.has(GrantKey(AssistantToolId.WEB_SEARCH, null)))
        grants.grant(key); grants.clear()
        assertFalse(grants.has(key)); assertFalse(grants.has(GrantKey(AssistantToolId.WEB_SEARCH, null)))
        assertEquals(key, GrantKey(ssh, "p/c"))
    }

    @Test fun `grants are safe under concurrent use`() {
        val grants = GrantStore()
        val threads = (0 until 4).map { n -> Thread { repeat(500) { grants.grant(GrantKey(ssh, "$n/$it")); grants.has(key); if (it % 100 == 0) grants.clear(ssh) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
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
