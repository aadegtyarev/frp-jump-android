package ru.adsrv.frpjump.mqtt

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** One MQTT packet as the fake broker reads it. */
private class Packet(val type: Int, val body: ByteArray, val flags: Int = 0) {
    /** CONNECT: the protocol level (5 or 4). */
    val level: Int get() = body[6].toInt()

    /** CONNECT: whether a user name is given. */
    val hasUser: Boolean get() = body[7].toInt() and 0x80 != 0

    val packetId: Int get() = (body[0].toInt() and 0xff shl 8) or (body[1].toInt() and 0xff)

    /** SUBSCRIBE / UNSUBSCRIBE: the first topic filter. */
    fun filter(v5: Boolean): String {
        var at = 2
        if (v5) at += 1 + body[at] // properties (HiveMQ sends a subscription identifier), short here
        val length = (body[at].toInt() and 0xff shl 8) or (body[at + 1].toInt() and 0xff)
        return String(body, at + 2, length, Charsets.UTF_8)
    }
}

/** A connection to the fake broker, speaking raw MQTT packets. */
private class Conn(private val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()

    fun read(): Packet? = try {
        val header = input.readUnsignedByte()
        var length = 0
        var shift = 0
        do {
            val b = input.readUnsignedByte()
            length = length or (b and 0x7f shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        Packet(header shr 4, ByteArray(length).also { input.readFully(it) }, header and 0x0f)
    } catch (e: EOFException) {
        null
    } catch (e: IOException) {
        null
    }

    fun send(header: Int, body: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(body.size) // bodies here are under 128 bytes
        out.write(body)
        output.write(out.toByteArray())
        output.flush()
    }

    fun send(header: Int, vararg body: Int) = send(header, ByteArray(body.size) { body[it].toByte() })

    fun publish(topic: String, payload: String, retain: Boolean, v5: Boolean) {
        val t = topic.toByteArray()
        val body = ByteArrayOutputStream()
        body.write(t.size shr 8)
        body.write(t.size and 0xff)
        body.write(t)
        if (v5) body.write(0)
        body.write(payload.toByteArray())
        send(0x30 or (if (retain) 1 else 0), body.toByteArray())
    }

    fun close() = socket.close()
}

/** A broker on a local port that runs [script] for each connection, numbered from 0. */
private class FakeBroker(private val script: (Conn, Int) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val connects: MutableList<Packet> = Collections.synchronizedList(mutableListOf())
    val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true) {
            var n = 0
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                val index = n++
                thread(isDaemon = true) { runCatching { script(Conn(socket), index) }.exceptionOrNull()?.let { received += "script failed: $it" } }
            }
        }
    }

    /** Reads the CONNECT, recording it. */
    fun connect(conn: Conn): Packet = conn.read()!!.also { connects += it }

    /**
     * Serves an open connection: SUBACK with [subackCode] for each filter
     * ([codeFor] may override it), UNSUBACK, PINGRESP; [onSubscribe] after
     * each SUBACK; a PUBLISH is recorded ("pub <topic> <payload> q<qos> r<retain>")
     * and a QoS 1 one answered with a PUBACK of [pubackCode] (MQTT 5).
     */
    fun serve(conn: Conn, v5: Boolean, codeFor: (String) -> Int = { 2 }, pubackCode: (String) -> Int = { 0 }, onSubscribe: (String) -> Unit = {}) {
        while (true) {
            val p = conn.read() ?: return
            when (p.type) {
                3 -> {
                    val qos = p.flags shr 1 and 3
                    val length = (p.body[0].toInt() and 0xff shl 8) or (p.body[1].toInt() and 0xff)
                    val topic = String(p.body, 2, length, Charsets.UTF_8)
                    var at = 2 + length
                    val id = intArrayOf(p.body.getOrElse(at) { 0 }.toInt() and 0xff, p.body.getOrElse(at + 1) { 0 }.toInt() and 0xff)
                    if (qos > 0) at += 2
                    if (v5) at += 1 + p.body[at] // properties, short here
                    received += "pub $topic ${String(p.body, at, p.body.size - at, Charsets.UTF_8)} q$qos r${p.flags and 1}"
                    if (qos == 1) if (v5) conn.send(0x40, id[0], id[1], pubackCode(topic), 0) else conn.send(0x40, id[0], id[1])
                }
                8 -> {
                    val filter = p.filter(v5)
                    received += "sub $filter"
                    if (v5) conn.send(0x90, p.body[0].toInt(), p.body[1].toInt(), 0, codeFor(filter)) else conn.send(0x90, p.body[0].toInt(), p.body[1].toInt(), codeFor(filter))
                    onSubscribe(filter)
                }
                10 -> {
                    received += "unsub ${p.filter(v5)}"
                    if (v5) conn.send(0xB0, p.body[0].toInt(), p.body[1].toInt(), 0, 0) else conn.send(0xB0, p.body[0].toInt(), p.body[1].toInt())
                }
                12 -> conn.send(0xD0)
                14 -> return conn.close()
            }
        }
    }

    override fun close() = server.close()
}

class MqttBrokerTest {
    private val logins = MqttLogins(MemoryStorage(), FakeKeystore().box("k"))
    private val sessions = mutableListOf<MqttSession>()
    private val brokers = mutableListOf<FakeBroker>()

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        brokers.forEach { it.close() }
    }

    private fun broker(script: FakeBroker.(Conn, Int) -> Unit): FakeBroker {
        lateinit var broker: FakeBroker
        broker = FakeBroker { conn, index -> broker.script(conn, index) }
        return broker.also { brokers += it }
    }

    private fun session(broker: FakeBroker): MqttSession {
        val env = MqttEnvironment(logins, PinStore(MemoryStorage()), HiveMqConnector(), ExecutorScheduler())
        return MqttSession(1, "p", "c", "wb", 1883, broker.port, env).also {
            sessions += it
            it.connect()
        }
    }

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = runBlocking { withTimeout(10_000) { first(predicate) } }

    private val connackV5 = intArrayOf(0, 0, 0)
    private val connackV3 = intArrayOf(0, 0)

    @Test
    fun `MQTT 5 opens and the snapshot reads the retained tree, then unsubscribes`() {
        val broker = broker { conn, _ ->
            connect(conn)
            conn.send(0x20, *connackV5)
            serve(conn, v5 = true, onSubscribe = { filter ->
                if (filter == "#") {
                    conn.publish("/devices/a/controls/K1", "1", retain = true, v5 = true)
                    conn.publish("b/c", "x", retain = true, v5 = true)
                }
            })
        }
        val session = session(broker)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.await { it is MqttState.Open })
        assertEquals(SnapshotState.Done(SnapshotEnd.QUIET), session.snapshot.await { it is SnapshotState.Done })
        assertEquals(2, session.tree.topicCount)
        assertTrue(session.tree.node(MqttPath.parse("/devices/a/controls/K1"))!!.value!!.retained)
        runBlocking { withTimeout(5_000) { while ("unsub #" !in broker.received) kotlinx.coroutines.delay(20) } }

        val connect = broker.connects.single()
        assertEquals(5, connect.level)
        // Clean start, keep alive 30 s, a client id "frpjump-…".
        assertTrue(connect.body[7].toInt() and 0x02 != 0)
        assertEquals(30, (connect.body[8].toInt() shl 8) or connect.body[9].toInt())
        assertTrue(String(connect.body, Charsets.ISO_8859_1).contains("frpjump-"))
    }

    @Test
    fun `a broker that refuses MQTT 5 gets 3_1_1`() {
        val broker = broker { conn, _ ->
            val connect = connect(conn)
            if (connect.level == 5) {
                conn.send(0x20, 0, 0x84, 0)
                conn.close()
            } else {
                conn.send(0x20, *connackV3)
                serve(conn, v5 = false)
            }
        }
        val session = session(broker)
        assertEquals(MqttState.Open(MqttVersion.V3_1_1), session.state.await { it is MqttState.Open })
        assertEquals(listOf(5, 4), broker.connects.map { it.level })
    }

    @Test
    fun `a broker that answers MQTT 5 with a 3_1_1 refusal gets 3_1_1`() {
        // What Mosquitto 1.5 and older send: a 3.1.1 CONNACK "unacceptable protocol version".
        val broker = broker { conn, _ ->
            val connect = connect(conn)
            if (connect.level == 5) {
                conn.send(0x20, 0, 1)
                conn.close()
            } else {
                conn.send(0x20, *connackV3)
                serve(conn, v5 = false)
            }
        }
        assertEquals(MqttState.Open(MqttVersion.V3_1_1), session(broker).state.await { it is MqttState.Open })
    }

    @Test
    fun `a broker that closes before any CONNACK gets 3_1_1`() {
        val broker = broker { conn, _ ->
            val connect = connect(conn)
            if (connect.level == 5) {
                conn.close()
            } else {
                conn.send(0x20, *connackV3)
                serve(conn, v5 = false)
            }
        }
        assertEquals(MqttState.Open(MqttVersion.V3_1_1), session(broker).state.await { it is MqttState.Open })
    }

    @Test
    fun `a refused anonymous login asks, and a remembered one is kept`() {
        val broker = broker { conn, _ ->
            val connect = connect(conn)
            if (!connect.hasUser) {
                conn.send(0x20, 0, 0x87, 0)
                conn.close()
            } else {
                conn.send(0x20, *connackV5)
                serve(conn, v5 = true)
            }
        }
        val session = session(broker)
        assertEquals(MqttState.NeedsLogin(null, wrong = false), session.state.await { it is MqttState.NeedsLogin })
        session.login("user", "pw", remember = true)
        assertEquals(MqttState.Open(MqttVersion.V5), session.state.await { it is MqttState.Open })
        assertEquals("pw", logins.get("p", "c")!!.password)
    }

    @Test
    fun `a broker that refuses # says so and keeps the session open`() {
        val broker = broker { conn, _ ->
            connect(conn)
            conn.send(0x20, *connackV5)
            serve(conn, v5 = true, codeFor = { if (it == "#") 0x87 else 2 })
        }
        val session = session(broker)
        assertEquals(SnapshotState.Refused, session.snapshot.await { it is SnapshotState.Refused })
        assertEquals(setOf("#"), session.refused.value)
        assertTrue(session.state.value is MqttState.Open)
    }

    private fun MqttSession.publishNow(topic: String, payload: String, qos: Int, retain: Boolean = false): PublishAnswer {
        val answer = java.util.concurrent.CompletableFuture<PublishAnswer>()
        publish(topic, payload.toByteArray(), qos, retain) { answer.complete(it) }
        return answer.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `MQTT 5 publish answers accepted, refused with the reason code, and QoS 0 unconfirmed`() {
        val broker = broker { conn, _ ->
            connect(conn)
            conn.send(0x20, *connackV5)
            serve(conn, v5 = true, pubackCode = { if (it == "denied") 0x87 else 0 })
        }
        val session = session(broker)
        session.state.await { it is MqttState.Open }
        assertEquals(PublishAnswer.Accepted, session.publishNow("/devices/x/controls/K1/on", "1", qos = 1, retain = true))
        assertEquals(PublishAnswer.Refused(0x87, null), session.publishNow("denied", "1", qos = 1))
        assertEquals(PublishAnswer.Unconfirmed, session.publishNow("a/b", "x", qos = 0))
        assertTrue("pub /devices/x/controls/K1/on 1 q1 r1" in broker.received)
        assertTrue("pub a/b x q0 r0" in broker.received)
    }

    @Test
    fun `MQTT 3_1_1 publish is sent, not confirmed`() {
        val broker = broker { conn, _ ->
            if (connect(conn).level == 5) return@broker conn.close()
            conn.send(0x20, *connackV3)
            serve(conn, v5 = false)
        }
        val session = session(broker)
        assertEquals(MqttState.Open(MqttVersion.V3_1_1), session.state.await { it is MqttState.Open })
        assertEquals(PublishAnswer.Unconfirmed, session.publishNow("a/b", "x", qos = 1))
        assertTrue("pub a/b x q1 r0" in broker.received)
    }
}
