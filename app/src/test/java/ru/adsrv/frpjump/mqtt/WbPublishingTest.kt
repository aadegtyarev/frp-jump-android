package ru.adsrv.frpjump.mqtt

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.*
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage

class WbPublishingTest {
    private val path = wbControlPath("synthetic", "text")
    private val meta = ControlMeta(type = "text")
    private val control = WbControl("text", path, "Synthetic", meta, "initial", emptyList())
    private class Fixture {
        val scheduler = FakeScheduler()
        lateinit var events: LinkEvents
        data class Sent(val topic: String, val text: String, val qos: Int, val retain: Boolean)
        val sent = mutableListOf<Sent>()
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) { done(true) }
            override fun unsubscribe(filter: String) = Unit
            override fun close() = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) {
                sent += Sent(topic, payload.toString(Charsets.UTF_8), qos, retain)
                done(PublishAnswer.Accepted)
            }
        }
        val connector = MqttConnector { _, e, done -> events = e; done(ConnectOutcome.Opened(link)) }
        val session = MqttSession(1, "synthetic", "c", "synthetic", 1883, 1,
            MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("synthetic")), PinStore(MemoryStorage()), connector, scheduler))
        init { session.connect() }
        fun message(topic: String, text: String) = events.message(topic, text.toByteArray(), true, 0)
    }
    private fun Fixture.type(json: String = """{"type":"text"}""") = message(path.format() + "/meta", json)
    private fun Fixture.ask(c: WbControl = control, owner: WbWriteOwner? = session.wbWriteOwner): PublishAnswer? {
        var answer: PublishAnswer? = null
        session.publishWb(owner, c, "exact\nvalue") { answer = it }
        return answer
    }

    @Test fun `current writable control uses only its on topic QoS1 non retained and exact payload`() {
        val f = Fixture(); f.type()
        assertEquals(PublishAnswer.Accepted, f.ask())
        assertEquals(listOf(Fixture.Sent(path.format() + "/on", "exact\nvalue", 1, false)), f.sent)
    }

    @Test fun `JSON readonly blocks only WB while generic Publish stays unrestricted`() {
        val f = Fixture(); f.type("""{"type":"text","readonly":true}""")
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), f.ask(control.copy(meta = meta.copy(readonly = true))))
        assertTrue(f.sent.isEmpty())
        var answer: PublishAnswer? = null
        f.session.publish(path.format() + "/on", "manual".toByteArray(), 2, true) { answer = it }
        assertEquals(PublishAnswer.Accepted, answer)
        assertEquals(listOf(Fixture.Sent(path.format() + "/on", "manual", 2, true)), f.sent)
    }

    @Test fun `one-field readonly is enforced with JSON per-field precedence preserved`() {
        val f = Fixture()
        f.message(path.format() + "/meta/type", "text")
        f.message(path.format() + "/meta/readonly", "1")
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), f.ask())
        f.type("""{"type":"text","readonly":false}""")
        assertEquals(PublishAnswer.Accepted, f.ask())
    }

    @Test fun `meta queued before an old writable callback is rechecked at actual actor send`() {
        val f = Fixture(); f.type()
        val owner = f.session.wbWriteOwner
        var answer: PublishAnswer? = null
        f.scheduler.post {
            f.type("""{"type":"text","readonly":true}""")
            f.session.publishWb(owner, control, "stale") { answer = it }
        }
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), answer)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `missing cleared hidden and unsupported metadata cannot authorize a plugin send`() {
        val f = Fixture()
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), f.ask())
        f.type("""{"type":"text","hidden":true}""")
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), f.ask(control.copy(meta = meta.copy(hidden = true))))
        f.type("""{"type":"future-type"}""")
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), f.ask(control.copy(meta = meta.copy(type = "future-type"))))
        f.type(); f.session.refreshTree()
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), f.ask())
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `a writable but changed control never retargets an old payload to new metadata`() {
        val f = Fixture(); f.type("""{"type":"value","min":10}""")
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), f.ask())
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `old client owner is rejected after reconnect but a fresh action works`() {
        val f = Fixture(); f.type()
        val old = f.session.wbWriteOwner
        f.events.lost()
        assertEquals(PublishAnswer.Failed(PublishFailure.NOT_CONNECTED), f.ask(owner = old))
        f.scheduler.advanceBy(1000)
        assertNotSame(old, f.session.wbWriteOwner)
        assertEquals(PublishAnswer.Failed(PublishFailure.NOT_CONNECTED), f.ask(owner = old))
        assertTrue(f.sent.isEmpty())
        assertEquals(PublishAnswer.Accepted, f.ask())
    }

    @Test fun `an owner of another session and a closed owner cannot publish`() {
        val a = Fixture(); a.type()
        val b = Fixture(); b.type()
        assertEquals(PublishAnswer.Failed(PublishFailure.NOT_CONNECTED), b.ask(owner = a.session.wbWriteOwner))
        val owner = a.session.wbWriteOwner
        a.session.close()
        assertEquals(PublishAnswer.Failed(PublishFailure.NOT_CONNECTED), a.ask(owner = owner))
        assertTrue(a.sent.isEmpty()); assertTrue(b.sent.isEmpty())
    }

    @Test fun `equal timestamp and payload do not let an old answer change a newer write or wo feedback`() {
        val old = WbWrite(path, "1", false, 100)
        val newer = WbWrite(path, "1", false, 100)
        val state = kotlinx.coroutines.flow.MutableStateFlow(WbWriteBook(mapOf(path to old), mapOf(path to "0")))
        state.value = state.value.copy(writes = state.value.writes + (path to newer))
        assertSame(newer, state.value.writes[path])
        val book = state.value
        assertSame(book, book.answered(old, PublishAnswer.Accepted, 200, true))
        val accepted = book.answered(newer, PublishAnswer.Accepted, 200, true)
        assertEquals("1", accepted.sent[path])
        assertEquals(PublishAnswer.Accepted, accepted.writes[path]?.answer)
    }

    @Test fun `failed current wo write preserves last successful sent value`() {
        val write = WbWrite(path, "1", false, 100)
        val book = WbWriteBook(mapOf(path to write), mapOf(path to "0"))
        val failed = book.answered(write, PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), 200, true)
        assertEquals("0", failed.sent[path])
        assertEquals(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE), failed.writes[path]?.answer)
    }
}
