package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
