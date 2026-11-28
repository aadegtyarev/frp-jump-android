package ru.adsrv.frpjump.assistant.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.settings.*
import ru.adsrv.frpjump.ssh.MemoryStorage

class MqttDispatchCheckTest {
    private val target = GrantTarget("p", "c")
    private val tool = AssistantToolId.MQTT_PUBLISH
    private val key = GrantKey(tool.actionClass, target)

    @Test fun `explicit approval survives only while the tool remains enabled without a switch-off`() {
        val world = FakeGrantWorld(); val grants = SessionGrants(world)
        val support = ConfirmationSupport({ ConfirmationPolicy() }, { true }, grants = grants)
        val check = mqttDispatchCheck(support, grants.toolOffCount(tool), null, target)!!
        assertTrue(check())
        world.switchOff(tool); assertFalse(check()); world.off.clear(); assertFalse(check())
    }

    @Test fun `session and global bypass must still be the same valid basis`() {
        val world = FakeGrantWorld(); val grants = SessionGrants(world)
        var policy = ConfirmationPolicy(global = ConfirmationMode.OFF)
        val support = ConfirmationSupport({ policy }, { true }, grants = grants)
        val global = mqttDispatchCheck(support, 0, NoQuestion.OFF, target)!!
        assertTrue(global()); policy = ConfirmationPolicy(); assertFalse(global())
        assertTrue(grants.record(key, tool, grants.stamp(target)!!, grants.coverableBy(tool)))
        assertEquals(setOf(tool), grants.coverableBy(tool))
        assertFalse(grants.covers(AssistantToolId.SSH_COMMAND, GrantKey(AssistantToolId.SSH_COMMAND.actionClass, target)))
        val session = mqttDispatchCheck(support, 0, NoQuestion.SESSION, target)!!
        assertTrue(session()); world.period = 2L; assertFalse(session())
    }

    @Test fun `persistent basis is validated before queue and checks only memory after`() = runBlocking {
        val fake = FakeGrantWorld(); var allowDisk = true
        val world = object : GrantWorld by fake {
            override fun persistentFingerprint(target: GrantTarget): PersistentFp {
                check(allowDisk) { "scheduler must not read fingerprint" }
                return fake.persistentFingerprint(target)
            }
        }
        val settings = SettingsStore(MemoryStorage()).apply { update { it.copy(assistantEnabled = true, assistantMqttPublish = true) } }
        val connection = ConnectionGrants(settings, world)
        val session = SessionGrants(world)
        assertEquals(RecordResult.RECORDED, connection.record(key, tool, connection.stamp(target)!!, setOf(tool)))
        val support = ConfirmationSupport({ ConfirmationPolicy() }, { true }, grants = session, connection = connection)
        val check = mqttDispatchCheck(support, 0, NoQuestion.CONNECTION, target)!!
        allowDisk = false
        assertTrue(check())
        connection.revoke(target.profileId, target.connectionId)
        assertFalse(check())
    }
}
