package ru.adsrv.frpjump.ops.mqtt

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.mqtt.PublishRequest
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.MqttFailure
import ru.adsrv.frpjump.ops.MqttRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.RemoteOutcome
import ru.adsrv.frpjump.ui.mqtt.toPublishAnswer
import java.io.File

/** Generic publish, moved out of the MQTT ViewModel: every answer of the session maps to a result and back. */
class MqttPublishTest {
    private val target = MqttTarget("p", "c")
    private val request = PublishRequest("a/b", "héllo", 1, true, json = false, field = "a/b")
    private val session = FakeWriteSession()
    private val op = MqttPublish(FakeWriteHost(session))

    private val cases: List<Pair<PublishAnswer, OpResult<MqttDelivery>>> = listOf(
        PublishAnswer.Accepted to OpResult.Done(MqttDelivery.ACCEPTED),
        PublishAnswer.Unconfirmed to OpResult.Done(MqttDelivery.UNCONFIRMED),
        PublishAnswer.Refused(0x87, "no") to OpResult.Failed(MqttFailure.BrokerRefused(0x87, "no"), RemoteOutcome.KNOWN),
        PublishAnswer.Refused(0x80, null) to OpResult.Failed(MqttFailure.BrokerRefused(0x80, null), RemoteOutcome.KNOWN),
        PublishAnswer.Failed(PublishFailure.NOT_CONNECTED) to OpResult.Refused(MqttRefusal.NotConnected),
        PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE) to OpResult.Refused(MqttRefusal.NotWritable),
        PublishAnswer.Failed(PublishFailure.LOST) to OpResult.Failed(MqttFailure.ConnectionLost, RemoteOutcome.UNKNOWN),
        PublishAnswer.Failed(PublishFailure.TIMEOUT) to OpResult.Failed(MqttFailure.NoAnswer, RemoteOutcome.UNKNOWN),
        PublishAnswer.Failed(PublishFailure.OTHER, "IllegalArgumentException") to OpResult.Failed(MqttFailure.Other("IllegalArgumentException"), RemoteOutcome.UNKNOWN),
        PublishAnswer.Failed(PublishFailure.OTHER) to OpResult.Failed(MqttFailure.Other(null), RemoteOutcome.UNKNOWN),
    )

    @Test fun `declares its name and a remote change with no grant target`() {
        assertEquals("mqtt_publish", op.name)
        assertEquals(Effect.CHANGE_REMOTE, op.effect)
        assertNull(op.target(MqttPublishInput(target, request)))
    }

    @Test fun `every answer maps to its result and the screen gets the same answer back`() = runBlocking {
        assertEquals(PublishFailure.entries.toSet(), cases.mapNotNull { (it.first as? PublishAnswer.Failed)?.failure }.toSet())
        for ((answer, expected) in cases) {
            assertEquals(answer.toString(), expected, answer.toOpResult())
            assertEquals(answer.toString(), answer, expected.toPublishAnswer())
            val s = FakeWriteSession()
            val job = async(start = CoroutineStart.UNDISPATCHED) { MqttPublish(FakeWriteHost(s))(MqttPublishInput(target, request)) }
            s.pending.single()(answer)
            assertEquals(answer.toString(), expected, job.await())
        }
    }

    @Test fun `it sends the request as UTF-8 with its QoS and retain through the session and waits for the one answer`() = runBlocking {
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(MqttPublishInput(target, request)) }
        assertEquals(listOf(FakeWriteSession.Sent("a/b", "héllo", 1, true)), session.sent)
        assertFalse(job.isCompleted)
        session.pending.single()(PublishAnswer.Accepted)
        assertEquals(OpResult.Done(MqttDelivery.ACCEPTED), job.await())
    }

    @Test fun `generic publish does not look at WB writability or ownership`() = runBlocking {
        session.writable = false
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(MqttPublishInput(target, request)) }
        session.pending.single()(PublishAnswer.Accepted)
        assertEquals(OpResult.Done(MqttDelivery.ACCEPTED), job.await())
    }

    @Test fun `no open session for the target is refused as not connected and nothing is sent`() = runBlocking {
        assertEquals(OpResult.Refused(MqttRefusal.NotConnected), MqttPublish(FakeWriteHost(null))(MqttPublishInput(target, request)))
        assertEquals(OpResult.Refused(MqttRefusal.NotConnected), op(MqttPublishInput(MqttTarget("p", "other"), request)))
        assertTrue(session.sent.isEmpty())
    }

    @Test fun `a caller that stops waiting does not take the send back and a late answer is harmless`() = runBlocking {
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(MqttPublishInput(target, request)) }
        job.cancel()
        assertEquals(1, session.sent.size)
        session.pending.single()(PublishAnswer.Accepted)
        assertTrue(job.isCancelled)
    }

    @Test fun `the ViewModel keeps no send logic and the memory stays in the ViewModel's scope`() {
        val source = File("src/main/java/ru/adsrv/frpjump/ui/mqtt/MqttViewModel.kt").readText()
        val publish = source.substringAfter("    fun publish(request: PublishRequest").substringBefore("    fun refreshTree()")
        assertTrue(publish.contains("viewModelScope.launch {"))
        assertTrue(publish.contains("launch(Dispatchers.IO) {"))
        assertTrue(publish.contains("synchronized(memory)"))
        assertFalse(publish.contains("s.publish("))
        assertFalse(publish.contains("appScope"))
        assertFalse(publish.contains("GlobalScope"))
    }
}
