package ru.adsrv.frpjump.ops.mqtt

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.MqttVersion
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.mqtt.WbWriteOwner
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.ControlMeta
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWriteBook
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.wbControlPath
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.MqttWriteFailure
import ru.adsrv.frpjump.ops.MqttWriteRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.RemoteOutcome
import java.io.File

/** Wiren Board control write, moved out of the MQTT ViewModel: the guards, the double tap and the exact-write answer. */
class MqttWbWriteTest {
    private val target = MqttTarget("p", "c")
    private val path = wbControlPath("dev", "text")
    private val control = WbControl("text", path, "T", ControlMeta(type = "text"), "x", emptyList())
    private val session = FakeWriteSession().also { it.owner = WbWriteOwner() }
    private val owner = session.owner
    private val book = MutableStateFlow(WbWriteBook())
    private var sending = 0
    private var now = 1_000L
    private val op = MqttWbWrite(FakeWriteHost(session), book, onSending = { sending++ }, clock = { now })
    private fun input(payload: String, o: WbWriteOwner? = owner) = MqttWbWriteInput(target, o, control, payload)

    @Test fun `declares its name and a remote change with no grant target`() {
        assertEquals("wb_write", op.name)
        assertEquals(Effect.CHANGE_REMOTE, op.effect)
        assertNull(op.target(input("1")))
    }

    @Test fun `the owner and the writability are guarded and nothing is recorded or sent when they fail`() = runBlocking {
        assertEquals(OpResult.Refused(MqttWriteRefusal.NotOwner), refusedAtOnce { op(input("1", o = WbWriteOwner())) })
        assertEquals(OpResult.Refused(MqttWriteRefusal.NotOwner), refusedAtOnce { op(input("1", o = null)) })
        session.writable = false
        assertEquals(OpResult.Refused(MqttWriteRefusal.NotWritable()), refusedAtOnce { op(input("1")) })
        assertEquals(OpResult.Refused(MqttWriteRefusal.NotConnected()), MqttWbWrite(FakeWriteHost(null), book)(input("1")))
        assertTrue(session.sentWb.isEmpty())
        assertTrue(book.value.writes.isEmpty())
        assertEquals(0, sending)
    }

    @Test fun `a write is recorded before the send, sent once to the session and answered on exactly that write`() = runBlocking {
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(input("hello")) }
        assertEquals(1, sending)
        val write = book.value.writes.getValue(path)
        assertEquals("hello", write.asked)
        assertSame(owner, write.owner)
        assertNull(write.answer)
        assertEquals(listOf(control to "hello"), session.sentWb)
        now = 1_500
        session.pending.single()(PublishAnswer.Accepted)
        assertEquals(OpResult.Done(MqttDelivery.ACCEPTED), job.await())
        assertEquals(PublishAnswer.Accepted, book.value.writes.getValue(path).answer)
        assertEquals(1_500L, book.value.writes.getValue(path).answeredAt)
    }

    @Test fun `a double tap while the control waits is blocked and sends nothing`() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { op(input("1")) }
        assertEquals(OpResult.Refused(MqttWriteRefusal.Blocked), refusedAtOnce { op(input("2")) })
        assertEquals(1, session.sentWb.size)
        assertEquals(1, sending)
        assertEquals("1", book.value.writes.getValue(path).asked)
        session.pending.single()(PublishAnswer.Failed(PublishFailure.LOST))
        first.await()
        // Answered: the next write goes (the failure stays until it, and does not block).
        val second = async(start = CoroutineStart.UNDISPATCHED) { op(input("3")) }
        assertEquals(2, session.sentWb.size)
        session.pending.last()(PublishAnswer.Accepted)
        second.await()
        Unit
    }

    @Test fun `a late answer changes only its own write`() = runBlocking {
        val old = async(start = CoroutineStart.UNDISPATCHED) { op(input("old")) }
        val oldAnswer = session.pending.single()
        // The book is replaced by a newer write of the same control (as a refresh after the old one ended would allow).
        book.value = WbWriteBook()
        val newer = async(start = CoroutineStart.UNDISPATCHED) { op(input("newer")) }
        oldAnswer(PublishAnswer.Accepted)
        assertEquals(OpResult.Done(MqttDelivery.ACCEPTED), old.await())
        assertEquals("newer", book.value.writes.getValue(path).asked)
        assertNull(book.value.writes.getValue(path).answer)
        session.pending[1](PublishAnswer.Unconfirmed)
        newer.await()
        assertEquals(PublishAnswer.Unconfirmed, book.value.writes.getValue(path).answer)
    }

    @Test fun `an answer after the owner is gone is not recorded`() = runBlocking {
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(input("1")) }
        session.owner = null
        session.pending.single()(PublishAnswer.Accepted)
        job.await()
        assertNull(book.value.writes.getValue(path).answer)
        Unit
    }

    @Test fun `a caller that stops waiting still gets the answer recorded`() = runBlocking {
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(input("1")) }
        job.cancel()
        session.pending.single()(PublishAnswer.Accepted)
        assertEquals(PublishAnswer.Accepted, book.value.writes.getValue(path).answer)
    }

    @Test fun `what the session answers is recorded and mapped, the session's own refusals included`() = runBlocking {
        suspend fun answerWith(a: PublishAnswer): OpResult<MqttDelivery> {
            book.value = WbWriteBook()
            val before = session.pending.size
            val job = async(start = CoroutineStart.UNDISPATCHED) { op(input("1")) }
            session.pending[before](a)
            return job.await().also { assertEquals(a, book.value.writes.getValue(path).answer) }
        }
        assertEquals(OpResult.Refused(MqttWriteRefusal.NotWritable()), answerWith(PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE)))
        assertEquals(OpResult.Refused(MqttWriteRefusal.NotConnected()), answerWith(PublishAnswer.Failed(PublishFailure.NOT_CONNECTED)))
        assertEquals(OpResult.Failed(MqttWriteFailure.NoAnswer(), RemoteOutcome.UNKNOWN), answerWith(PublishAnswer.Failed(PublishFailure.TIMEOUT)))
        assertEquals(OpResult.Failed(MqttWriteFailure.BrokerRefused(0x87, null), RemoteOutcome.KNOWN), answerWith(PublishAnswer.Refused(0x87, null)))
    }

    @Test fun `the write is recorded and onSending has run before the session is asked to send`() = runBlocking {
        var recorded: String? = "unset"
        var sendingAtCall = -1
        session.atPublishWb = { recorded = book.value.writes[path]?.asked; sendingAtCall = sending }
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(input("early")) }
        assertEquals("early", recorded)
        assertEquals(1, sendingAtCall)
        session.pending.single()(PublishAnswer.Accepted)
        job.await()
        Unit
    }

    @Test fun `an unanswered write of another owner does not block this owner's write`() = runBlocking {
        val o1 = owner
        val first = async(start = CoroutineStart.UNDISPATCHED) { op(input("1", o = o1)) }
        // The session's owner changes (a new accepted client); the old client's write is still waiting in the book.
        val o2 = WbWriteOwner()
        session.owner = o2
        val second = async(start = CoroutineStart.UNDISPATCHED) { refusedAtOnce { op(input("2", o = o2)) } }
        assertEquals(2, session.sentWb.size)
        assertSame(o2, book.value.writes.getValue(path).owner)
        session.pending[1](PublishAnswer.Accepted)
        assertEquals(OpResult.Done(MqttDelivery.ACCEPTED), second.await())
        first.cancel()
        Unit
    }

    private val woSwitch = control.copy(meta = ControlMeta(type = "wo-switch"))
    private val switch = control.copy(meta = ControlMeta(type = "switch"))
    private fun inputOf(c: WbControl, payload: String) = MqttWbWriteInput(target, owner, c, payload)
    private fun value(text: String) = TopicValue.of(text.toByteArray(), retain = true, qos = 1, at = 900, version = MqttVersion.V5, previous = null)

    @Test fun `a wo-switch's sent value is kept after an accepted write and not after a refused one`() = runBlocking {
        val ok = async(start = CoroutineStart.UNDISPATCHED) { op(inputOf(woSwitch, "1")) }
        session.pending.single()(PublishAnswer.Accepted)
        ok.await()
        assertEquals("1", book.value.sent[path])
        book.value = WbWriteBook()
        val before = session.pending.size
        val refused = async(start = CoroutineStart.UNDISPATCHED) { op(inputOf(woSwitch, "0")) }
        session.pending[before](PublishAnswer.Refused(0x87, null))
        refused.await()
        assertNull(book.value.sent[path])
        // A switch (not a wo-switch) keeps no sent value.
        val sw = async(start = CoroutineStart.UNDISPATCHED) { op(inputOf(switch, "1")) }
        session.pending.last()(PublishAnswer.Accepted)
        sw.await()
        assertNull(book.value.sent[path])
        Unit
    }

    @Test fun `a wo-switch compares with what was sent, not with the status topic`() = runBlocking {
        book.value = WbWriteBook(sent = mapOf(path to "1"))
        session.history = listOf(value("0"))
        val job = async(start = CoroutineStart.UNDISPATCHED) { op(inputOf(woSwitch, "1")) }
        // A wo-switch never waits for an echo, whatever either shows.
        assertFalse(book.value.writes.getValue(path).awaitsEcho)
        session.pending.single()(PublishAnswer.Accepted)
        job.await()
        Unit
    }

    @Test fun `a switch waits for an echo only when its status topic does not already show the value`() = runBlocking {
        session.history = listOf(value("1"))
        val same = async(start = CoroutineStart.UNDISPATCHED) { op(inputOf(switch, "1")) }
        assertFalse(book.value.writes.getValue(path).awaitsEcho)
        session.pending.single()(PublishAnswer.Accepted)
        same.await()
        book.value = WbWriteBook()
        session.history = listOf(value("0"))
        val before = session.pending.size
        val other = async(start = CoroutineStart.UNDISPATCHED) { op(inputOf(switch, "1")) }
        assertTrue(book.value.writes.getValue(path).awaitsEcho)
        session.pending[before](PublishAnswer.Accepted)
        other.await()
        Unit
    }

    @Test fun `the book is the ViewModel's own, the refresh speed-up is kept and no send logic is left there`() {
        val source = File("src/main/java/ru/adsrv/frpjump/ui/mqtt/MqttViewModel.kt").readText()
        assertTrue(source.contains("private val wbWrites = MutableStateFlow(WbWriteBook())"))
        assertTrue(source.contains("MqttWbWrite(writeHost, wbWrites, onSending = { writeWaiting = true })"))
        assertFalse(source.contains("book.answered("))
        assertFalse(source.substringAfter("    fun writeWb(").substringBefore("    /** The open session").contains("publishWb"))
        assertFalse(source.contains("writeBlocked("))
        for (name in listOf("mqtt/MqttSession.kt", "FrpJumpApp.kt")) {
            assertFalse(name, File("src/main/java/ru/adsrv/frpjump/$name").readText().contains("WbWriteBook"))
        }
    }
}
