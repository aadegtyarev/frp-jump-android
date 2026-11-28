package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.mqtt.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.tunnel.*
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.undo.PendingUndo

class AssistantMqttPublishTest {
    private val authority = MqttPublishAuthority { { true } }
    internal class Scheduler : MqttScheduler {
        val base = FakeScheduler(); var hold = false; val queue = ArrayDeque<() -> Unit>()
        override fun now() = base.now()
        override fun post(action: () -> Unit) { if (hold) queue.addLast(action) else base.post(action) }
        override fun schedule(delayMs: Long, action: () -> Unit) = base.schedule(delayMs, action)
        fun release() { hold = false; while (queue.isNotEmpty()) base.post(queue.removeFirst()) }
    }
    internal class Rig : AutoCloseable {
        val scheduler = Scheduler()
        val target = SavedTunnel("p", "Profile", "c", "MQTT", "device", 1883, PortKind.MQTT)
        var current: SavedTunnel? = target; var fingerprint: Any = "first"; var on = true; var port = 20000; var hideVersion = 0L; var loginVersion = 0L
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        val host = object : SavedTunnelHost {
            override fun find(ids: SavedTunnelIds) = current?.takeIf { ids == SavedTunnelIds(it.profileId, it.connectionId) }
            override fun isStopping(tunnel: SavedTunnel) = !on
            override fun isOn(tunnel: SavedTunnel) = on
            override fun pin(ids: SavedTunnelIds) = find(ids)?.let { fingerprint }
            override fun tabs(tunnel: SavedTunnel) = 0
        }
        val up = object : Operation<TunnelUpInput, Int> {
            override val name = "up"; override val effect = Effect.CHANGE_LOCAL; override val actionClass = ActionClass.APP_CHANGE
            override suspend fun invoke(input: TunnelUpInput): OpResult<Int> = error("must not start")
        }
        val down = object : Operation<TunnelDownTarget, Unit> {
            override val name = "down"; override val effect = Effect.CHANGE_LOCAL; override val actionClass = ActionClass.APP_CHANGE
            override suspend fun invoke(input: TunnelDownTarget): OpResult<Unit> = error("must not stop")
        }
        val writes = mutableListOf<List<Any>>()
        var answer: PublishAnswer = PublishAnswer.Accepted
        lateinit var events: LinkEvents
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) = done(true)
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) {
                writes += listOf(topic, payload.toString(Charsets.UTF_8), qos, retain); done(answer)
            }
            override fun close() = Unit
        }
        val storage = MemoryStorage()
        val environment = MqttEnvironment(MqttLogins(storage, FakeKeystore().box("mqtt-test")), PinStore(storage),
            MqttConnector { _, e, done -> events = e; done(ConnectOutcome.Opened(link)) }, scheduler)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessions = MqttSessions(environment, MutableStateFlow(setOf(profilePortKey("p", "device", 1883))), scope, Dispatchers.Unconfined)
        val session = sessions.open("p", "c", "device", 1883, port)
        val service = AssistantMqttPublish(AssistantTunnels(host, up, down), sessions, hidden, loginRevision = { loginVersion }, hideCount = { hideVersion }) { port }
        val tool = AssistantMqttPublishTool(service)
        override fun close() { scope.cancel() }
    }
    private fun args(payload: String = "hello", qos: Int = 1, retain: Boolean = true, topic: String = "devices/x") = buildJsonObject {
        put("profile_id", "p"); put("connection_id", "c"); put("topic", topic); put("payload", payload); put("qos", qos); put("retain", retain)
    }
    @Test fun `prepared exact message publishes once and distinguishes broker outcomes`() = runBlocking {
        Rig().use { r ->
            val a = args("", qos = 2)
            val change = r.tool.changeCheck(a) as ChangeCheck.Change
            assertEquals("", change.request.command); assertEquals("devices/x", change.request.mqtt!!.topic)
            assertTrue(change.request.mqtt!!.retain); assertEquals(2, change.request.mqtt!!.qos)
            assertEquals("Accepted by the broker.", withContext(SideEffectAdmission { true } + authority) { r.tool.execute(a) }.text)
            assertEquals(listOf(listOf("devices/x", "", 2, true)), r.writes)
            r.answer = PublishAnswer.Unconfirmed; r.tool.changeCheck(args()); assertTrue(withContext(SideEffectAdmission { true } + authority) { r.tool.execute(args()) }.text.contains("unconfirmed"))
            r.answer = PublishAnswer.Refused(135, "private broker reason"); r.tool.changeCheck(args()); val failed = withContext(SideEffectAdmission { true } + authority) { r.tool.execute(args()) }
            assertEquals(ToolOutcome.FAILED, failed.outcome); assertTrue(failed.text.contains("135")); assertFalse(failed.text.contains("private"))
        }
    }
    @Test fun `standard confirmation carries pinned identity and decline never publishes`() = runBlocking {
        Rig().use { r ->
            val a = args("hello\nworld")
            val support = ru.adsrv.frpjump.assistant.chat.ConfirmationSupport({ ru.adsrv.frpjump.settings.ConfirmationPolicy() }, { true },
                grants = ru.adsrv.frpjump.settings.SessionGrants(ru.adsrv.frpjump.settings.FakeGrantWorld()))
            val call = ToolCall("call", "mqtt_publish", a)
            var seen: ru.adsrv.frpjump.assistant.tools.ChangeRequest? = null
            val declined = ru.adsrv.frpjump.assistant.chat.authorizeCall(support, mapOf("mqtt_publish" to r.tool), call,
                wait = { request, _ -> seen = request; ru.adsrv.frpjump.assistant.chat.ConfirmationDecision.DENY }, onNotSaved = {}, io = Dispatchers.Unconfined)
            assertEquals(ToolOutcome.DECLINED, declined!!.outcome)
            assertEquals("hello\nworld", seen!!.command)
            assertEquals(listOf("Profile", "MQTT", "device", 1883), seen!!.mqtt!!.let { listOf(it.profile, it.name, it.device, it.port) })
            assertEquals(setOf(AssistantToolId.MQTT_PUBLISH), seen!!.sessionTools)
            assertTrue(r.writes.isEmpty())
            val allowed = ru.adsrv.frpjump.assistant.chat.authorizeCall(support, mapOf("mqtt_publish" to r.tool), call,
                wait = { _, _ -> ru.adsrv.frpjump.assistant.chat.ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined)
            assertNull(allowed)
            assertEquals(ToolOutcome.SUCCESS, withContext(SideEffectAdmission { true } + authority) { r.tool.execute(a) }.outcome)
            assertEquals(1, r.writes.size)
        }
    }

    @Test fun `invalid arguments and changed request publish nothing`() = runBlocking {
        Rig().use { r ->
            assertSame(ChangeCheck.Invalid, r.tool.changeCheck(args(topic = "bad/#")))
            assertSame(ChangeCheck.Invalid, r.tool.changeCheck(args(payload = "\uD800")))
            assertSame(ChangeCheck.Invalid, r.tool.changeCheck(args(topic = "x\uDC00")))
            assertEquals(ToolOutcome.FAILED, r.tool.execute(args(topic = "bad/#")).outcome)
            r.tool.changeCheck(args("first")); assertEquals(ToolOutcome.FAILED, r.tool.execute(args("different")).outcome)
            assertTrue(r.writes.isEmpty())
        }
    }
    @Test fun `dispatch revalidates target hidden state local port client and owner`() = runBlocking {
        for (change in listOf("fingerprint", "session", "port", "reconnect", "revoked", "cancelled", "hide-undo", "login", "grant", "admission-changes-target")) Rig().use { r ->
            val a = args(); assertTrue(r.tool.changeCheck(a) is ChangeCheck.Change)
            r.scheduler.hold = true
            if (change == "reconnect") r.events.lost()
            var admitted = true; var granted = true
            val guard = MqttPublishAuthority { { granted } }
            val pending = async(start = CoroutineStart.UNDISPATCHED, context = SideEffectAdmission { category ->
                if (category == "mqtt-dispatch" && change == "admission-changes-target") r.fingerprint = "changed"
                category != "mqtt-dispatch" || admitted
            } + guard) { r.tool.execute(a) }
            when (change) {
                "fingerprint" -> r.fingerprint = "changed"
                "session" -> r.hidden.value = setOf(PendingUndo.sessionKey(SessionKind.MQTT, r.session.id))
                "port" -> r.port = 20001
                "revoked" -> admitted = false
                "cancelled" -> pending.cancel()
                "hide-undo" -> r.hideVersion++
                "login" -> r.loginVersion++
                "grant" -> granted = false
            }
            r.scheduler.release()
            if (change != "cancelled") assertEquals(change, ToolOutcome.FAILED, pending.await().outcome) else pending.join()
            assertTrue(change, r.writes.isEmpty())
        }
    }
}
