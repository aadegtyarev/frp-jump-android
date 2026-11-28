package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.mqtt.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.mqtt.*
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.settings.AssistantTools

class AssistantMqttReadTest {
    private val args = buildJsonObject { put("profile_id", "p"); put("connection_id", "c") }
    private fun value(bytes: ByteArray) = TopicValue(bytes, 1, 123, true, false, true)
    private fun tool(result: OpResult<MqttReadSnapshot>) = AssistantMqttReadTool(MqttRead { result }, AssistantLimits())

    @Test fun `default off and setting round trip`() {
        val defaults = AssistantTools()
        assertFalse(defaults[AssistantToolId.MQTT_READ])
        assertTrue(defaults.with(AssistantToolId.MQTT_READ, true)[AssistantToolId.MQTT_READ])
        assertFalse(defaults.with(AssistantToolId.MQTT_READ, true).with(AssistantToolId.MQTT_READ, false)[AssistantToolId.MQTT_READ])
    }

    @Test fun `empty values and unavailable explorer are distinct`() = runBlocking {
        val empty = tool(OpResult.Done(MqttReadSnapshot(emptyList(), 0, 0))).execute(args)
        assertEquals(ToolOutcome.SUCCESS, empty.outcome)
        assertTrue(Json.parseToJsonElement(empty.text).jsonObject.getValue("messages").jsonArray.isEmpty())
        val absent = tool(OpResult.Refused(MqttReadRefusal.NotConnected)).execute(args)
        assertEquals(ToolOutcome.FAILED, absent.outcome)
        assertTrue(absent.text.contains("No connected MQTT explorer"))
    }

    @Test fun `filter validation and bounded utf8 output`() = runBlocking {
        val rows = listOf(MqttPath.parse("a") to value("hello".toByteArray()), MqttPath.parse("b") to value(byteArrayOf(0xff.toByte())),
            MqttPath.parse("c") to value(ByteArray(513)))
        val reader = tool(OpResult.Done(MqttReadSnapshot(rows, 2, 1)))
        val invalid = reader.execute(buildJsonObject { put("profile_id", "p"); put("connection_id", "c"); put("filter", "a/#/b") })
        assertEquals(ToolOutcome.FAILED, invalid.outcome)
        val out = reader.execute(args)
        val json = Json.parseToJsonElement(out.text).jsonObject
        val messages = json.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("hello", messages[0].getValue("payload").jsonPrimitive.content)
        assertEquals(JsonNull, messages[1].getValue("payload"))
        assertEquals(JsonNull, messages[2].getValue("payload"))
        assertTrue(messages[0].getValue("before_session").jsonPrimitive.boolean)
        assertEquals(2, json.getValue("ingress_dropped").jsonPrimitive.int)
    }

    @Test fun `escaped filter and oversized topic cannot exceed output budget`() = runBlocking {
        val rows = listOf(MqttPath.parse("x".repeat(10_000)) to value("v".toByteArray()))
        val reader = AssistantMqttReadTool(MqttRead { OpResult.Done(MqttReadSnapshot(rows, 0, 0)) }, AssistantLimits(toolOutputChars = 2000))
        val out = reader.execute(buildJsonObject { put("profile_id", "p"); put("connection_id", "c"); put("filter", "\\".repeat(1024)) })
        assertEquals(ToolOutcome.SUCCESS, out.outcome)
        assertTrue(out.text.length <= 1800)
        assertTrue(Json.parseToJsonElement(out.text).jsonObject.getValue("omitted").jsonPrimitive.boolean)
    }

    @Test fun `bounded tree values use real MQTT matching`() {
        val tree = TopicTree()
        for (topic in listOf("a/x", "a/y", "a/z", "\$SYS/x")) tree.receive(MqttPath.parse(topic), byteArrayOf(1), false, 0, MqttVersion.V5)
        assertEquals(2, tree.values(filterLevels("a/+"), 2).size)
        assertEquals(3, tree.values(filterLevels("#")).size)
        assertEquals("\$SYS/x", tree.values(filterLevels("\$SYS/#")).single().first.format())
    }
}

class AssistantMqttReadTargetTest {
    @Test fun `only exact live saved target and visible connected session may be read`() = runBlocking {
        val target = ru.adsrv.frpjump.ops.tunnel.SavedTunnel("p", "Profile", "c", "MQTT", "device", 1883, ru.adsrv.frpjump.ports.PortKind.MQTT)
        var saved: ru.adsrv.frpjump.ops.tunnel.SavedTunnel? = target
        var on = true
        var pin: Any? = "pin"
        val host = object : ru.adsrv.frpjump.ops.tunnel.SavedTunnelHost {
            override fun find(ids: ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds) = saved?.takeIf { it.profileId == ids.profileId && it.connectionId == ids.connectionId }
            override fun isStopping(tunnel: ru.adsrv.frpjump.ops.tunnel.SavedTunnel) = !on
            override fun isOn(tunnel: ru.adsrv.frpjump.ops.tunnel.SavedTunnel) = on
            override fun pin(ids: ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds) = pin
            override fun tabs(tunnel: ru.adsrv.frpjump.ops.tunnel.SavedTunnel) = 0
        }
        val up = object : Operation<ru.adsrv.frpjump.ops.tunnel.TunnelUpInput, Int> {
            override val name = "test"; override val effect = Effect.CHANGE_LOCAL; override val actionClass = ActionClass.APP_CHANGE
            override suspend fun invoke(input: ru.adsrv.frpjump.ops.tunnel.TunnelUpInput): OpResult<Int> = error("must not start")
        }
        val down = object : Operation<ru.adsrv.frpjump.ops.tunnel.TunnelDownTarget, Unit> {
            override val name = "test"; override val effect = Effect.CHANGE_LOCAL; override val actionClass = ActionClass.APP_CHANGE
            override suspend fun invoke(input: ru.adsrv.frpjump.ops.tunnel.TunnelDownTarget): OpResult<Unit> = error("must not stop")
        }
        val scheduler = FakeScheduler()
        var events: LinkEvents? = null
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) = done(true)
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) = error("must not publish")
            override fun close() = Unit
        }
        val connector = MqttConnector { _, e, done -> events = e; done(ConnectOutcome.Opened(link)) }
        val storage = ru.adsrv.frpjump.ssh.MemoryStorage()
        val environment = MqttEnvironment(MqttLogins(storage, ru.adsrv.frpjump.profiles.FakeKeystore().box("synthetic")), PinStore(storage), connector, scheduler)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val sessions = MqttSessions(environment, kotlinx.coroutines.flow.MutableStateFlow(setOf(ru.adsrv.frpjump.ports.profilePortKey("p", "device", 1883))), scope, kotlinx.coroutines.Dispatchers.Unconfined)
        val hidden = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
        var localPort = 20000
        val op = AssistantMqttRead(AssistantTunnels(host, up, down), sessions, hidden) { localPort }.operation
        val input = MqttReadInput(ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds("p", "c"), "#")
        assertEquals(OpResult.Refused(MqttReadRefusal.NotConnected), op(input))
        val session = sessions.open("p", "c", "device", 1883, 20000)
        assertTrue(op(input) is OpResult.Done)
        session.tree.receive(MqttPath.parse("a"), "value".toByteArray(), false, 0, MqttVersion.V5)
        assertEquals(1, (op(input) as OpResult.Done).value.values.size)
        assertEquals(OpResult.Refused(MqttReadRefusal.TargetUnavailable), op(input.copy(ids = ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds("other", "c"))))
        hidden.value = setOf(ru.adsrv.frpjump.undo.PendingUndo.sessionKey(ru.adsrv.frpjump.tunnel.SessionKind.MQTT, session.id))
        assertEquals(OpResult.Refused(MqttReadRefusal.NotConnected), op(input))
        hidden.value = emptySet(); localPort = 20001
        assertEquals(OpResult.Refused(MqttReadRefusal.NotConnected), op(input))
        localPort = 20000; on = false
        assertEquals(OpResult.Refused(MqttReadRefusal.NotConnected), op(input))
        on = true; saved = target.copy(port = 1884)
        assertEquals(OpResult.Refused(MqttReadRefusal.NotConnected), op(input))
        saved = target; events!!.lost()
        assertEquals(OpResult.Refused(MqttReadRefusal.NotConnected), op(input))
        sessions.close(session.id)
        saved = null
        assertEquals(OpResult.Refused(MqttReadRefusal.TargetUnavailable), op(input))
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }
}
