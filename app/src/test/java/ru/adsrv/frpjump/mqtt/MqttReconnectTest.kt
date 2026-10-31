package ru.adsrv.frpjump.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage

/** Time by hand: [post] runs at once (queued, never nested), [advanceBy] fires due timers in order. */
class FakeScheduler : MqttScheduler {
    var time = 0L
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false

    private class Timer(val at: Long, val seq: Int, val action: () -> Unit) {
        var cancelled = false
    }

    private val timers = mutableListOf<Timer>()
    private var seq = 0

    override fun now() = time

    override fun post(action: () -> Unit) {
        queue.addLast(action)
        if (draining) return
        draining = true
        while (queue.isNotEmpty()) queue.removeFirst()()
        draining = false
    }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val timer = Timer(time + delayMs, seq++, action)
        timers += timer
        return { timer.cancelled = true }
    }

    fun advanceBy(ms: Long) {
        val end = time + ms
        while (true) {
            val next = timers.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            timers -= next
            time = next.at
            post(next.action)
        }
        time = end
    }
}

class MqttReconnectTest {
    private val scheduler = FakeScheduler()

    /** Answers each attempt with the next of [outcomes] (refused once they run out); records when it was asked. */
    private inner class ScriptedConnector : MqttConnector {
        val outcomes = ArrayDeque<() -> ConnectOutcome>()
        val attemptsAt = mutableListOf<Long>()
        var events: LinkEvents? = null
        val subscribes = mutableListOf<String>()

        override fun connect(request: ConnectRequest, events: LinkEvents, done: (ConnectOutcome) -> Unit) {
            attemptsAt += scheduler.now()
            this.events = events
            done(outcomes.removeFirstOrNull()?.invoke() ?: ConnectOutcome.Refused(ConnectRefusal.CLOSED_BEFORE_CONNACK))
        }

        fun opened() = ConnectOutcome.Opened(object : MqttLink {
            override fun subscribe(filter: String, done: (granted: Boolean) -> Unit) {
                subscribes += filter
                done(true)
            }

            override fun unsubscribe(filter: String) = Unit

            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) = Unit

            override fun close() = Unit
        })
    }

    @Test
    fun `a dropped connection is tried after 1, 2 and 5 s, then every 10 s, and subscribes again`() {
        val connector = ScriptedConnector()
        val pins = PinStore(MemoryStorage()).apply { set("p", "c", listOf("a/+")) }
        val env = MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), pins, connector, scheduler)
        val session = MqttSession(1, "p", "c", "wb", 1883, 20000, env)
        connector.outcomes += { connector.opened() }
        session.connect()
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
        assertEquals(listOf("a/+", "#"), connector.subscribes)

        scheduler.advanceBy(5_000)
        connector.subscribes.clear()
        connector.events!!.lost()
        assertEquals(MqttState.Reconnecting, session.state.value)
        repeat(4) { connector.outcomes += { ConnectOutcome.Refused(ConnectRefusal.CLOSED_BEFORE_CONNACK) } }
        connector.outcomes += { connector.opened() }
        scheduler.advanceBy(60_000)

        val lostAt = 5_000L
        val retries = connector.attemptsAt.drop(1).map { it - lostAt }
        assertEquals(listOf(1_000L, 3_000L, 8_000L, 18_000L, 28_000L), retries)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
        // The pin and the snapshot again; the snapshot ended 1 s after its SUBACK.
        assertEquals(listOf("a/+", "#"), connector.subscribes)
        assertEquals(SnapshotState.Done(SnapshotEnd.QUIET), session.snapshot.value)

        // Open again: the next drop starts from 1 s.
        connector.attemptsAt.clear()
        connector.events!!.lost()
        connector.outcomes += { connector.opened() }
        scheduler.advanceBy(1_000)
        assertEquals(listOf(scheduler.time), connector.attemptsAt)
        assertTrue(session.state.value is MqttState.Open)
    }

    @Test
    fun `live values of known topics do not keep the snapshot going, new topics and re-sent values do`() {
        val connector = ScriptedConnector()
        val session = MqttSession(1, "p", "c", "wb", 1883, 20000, env(connector))
        connector.outcomes += { connector.opened() }
        session.connect()
        val events = connector.events!!
        events.message("/devices/x/controls/t", "21".toByteArray(), retain = true, qos = 0)
        // A live controller keeps sending new values: not snapshot messages.
        repeat(3) {
            scheduler.advanceBy(300)
            events.message("/devices/x/controls/t", "2$it.5".toByteArray(), retain = true, qos = 0)
        }
        scheduler.advanceBy(100)
        assertEquals(SnapshotState.Done(SnapshotEnd.QUIET), session.snapshot.value)

        // Refresh: the known value re-sent unchanged counts, so does a new topic; 1 s after the last, it ends.
        session.refreshTree()
        events.message("/devices/x/controls/t", "1".toByteArray(), retain = true, qos = 0)
        scheduler.advanceBy(600)
        events.message("/devices/x/controls/t", "1".toByteArray(), retain = true, qos = 0)
        scheduler.advanceBy(600)
        events.message("/devices/x/controls/u", "2".toByteArray(), retain = true, qos = 0)
        scheduler.advanceBy(900)
        assertEquals(SnapshotState.Reading, session.snapshot.value)
        scheduler.advanceBy(100)
        assertEquals(SnapshotState.Done(SnapshotEnd.QUIET), session.snapshot.value)

        // New topics that never stop: the 10 s cap.
        session.refreshTree()
        repeat(40) {
            events.message("/devices/y/$it", "v".toByteArray(), retain = true, qos = 0)
            scheduler.advanceBy(300)
        }
        assertEquals(SnapshotState.Done(SnapshotEnd.TIMED_OUT), session.snapshot.value)
    }

    @Test
    fun `a filter the client will not build is refused and the rest of the pass still goes out`() {
        val connector = ScriptedConnector()
        val subscribed = mutableListOf<String>()
        val pins = PinStore(MemoryStorage()).apply { set("p", "c", listOf("bad\u0000pin", "a/+")) }
        val env = MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), pins, connector, scheduler)
        val session = MqttSession(1, "p", "c", "wb", 1883, 20000, env)
        connector.outcomes += {
            ConnectOutcome.Opened(object : MqttLink {
                override fun subscribe(filter: String, done: (granted: Boolean) -> Unit) {
                    require('\u0000' !in filter) { "malformed" }
                    subscribed += filter
                    done(true)
                }

                override fun unsubscribe(filter: String) = Unit

                override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) = Unit

                override fun close() = Unit
            })
        }
        session.connect()
        assertEquals(listOf("a/+", "#"), subscribed)
        assertEquals(setOf("bad\u0000pin"), session.refused.value)
        assertEquals(SnapshotState.Reading, session.snapshot.value)
        // Asked again on the next pass, refused again; the good ones are not subscribed twice.
        session.open(MqttPath.parse("x"))
        assertEquals(listOf("a/+", "#", "x/#"), subscribed)
        assertEquals(setOf("bad\u0000pin"), session.refused.value)
    }

    private fun env(connector: MqttConnector) =
        MqttEnvironment(MqttLogins(MemoryStorage(), FakeKeystore().box("k")), PinStore(MemoryStorage()), connector, scheduler)

    @Test
    fun `a session closed while connecting stays ended and drops the late connection`() {
        var answer: ((ConnectOutcome) -> Unit)? = null
        var linkClosed = false
        val session = MqttSession(1, "p", "c", "wb", 1883, 20000, env { _, _, done -> answer = done })
        session.connect()
        session.close()
        answer!!(
            ConnectOutcome.Opened(object : MqttLink {
                override fun subscribe(filter: String, done: (granted: Boolean) -> Unit) = Unit

                override fun unsubscribe(filter: String) = Unit

                override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) = Unit

                override fun close() {
                    linkClosed = true
                }
            }),
        )
        assertEquals(MqttState.Ended, session.state.value)
        assertTrue(linkClosed)
    }

    @Test
    fun `a connector that throws fails the session instead of leaving it connecting`() {
        val session = MqttSession(1, "p", "c", "wb", 1883, 20000, env { _, _, _ -> throw IllegalStateException("boom") })
        session.connect()
        assertEquals(MqttState.Failed(MqttFailure.REFUSED, "IllegalStateException"), session.state.value)
    }

    @Test
    fun `a session closes when its tunnel goes away`() {
        val up = MutableStateFlow(setOf(profilePortKey("p", "wb", 1883)))
        val sessions = MqttSessions(env { _, _, _ -> }, up, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val session = sessions.open("p", "c", "wb", 1883, 20000)
        assertSame(session, sessions.open("p", "c", "wb", 1883, 20000))
        up.value = emptySet()
        assertEquals(MqttState.Ended, session.state.value)
        assertTrue(sessions.sessions.value.isEmpty())
        assertTrue(sessions.closedByTunnel(session.id))
    }
}
