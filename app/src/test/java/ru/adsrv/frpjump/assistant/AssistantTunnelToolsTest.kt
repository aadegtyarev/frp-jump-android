package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.AssistantToolFactory
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.ConfirmationSupport
import ru.adsrv.frpjump.assistant.chat.NOT_AUTHORIZED
import ru.adsrv.frpjump.assistant.chat.authorizeCall
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ops.tunnel.TunnelDownTarget
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.settings.ConfirmationPolicy
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.tunnel.TunnelRef

/** `start_tunnel` and `stop_tunnel` over fake saved tunnels and fake operations: nothing real starts or stops. */
class AssistantTunnelToolsTest {
    private fun args(profile: String = "p1", connection: String = "c1") = buildJsonObject { put("profile_id", profile); put("connection_id", connection) }

    private fun start(f: TunnelFixture, a: JsonObject = args()): ToolOutput = runBlocking { StartTunnelTool(f.tunnels).execute(a) }

    /** What the kernel does: authorize (the common gate), then run unless refused. */
    private class Asker(val answer: ConfirmationDecision = ConfirmationDecision.ONCE, var mode: ConfirmationMode = ConfirmationMode.ASK) {
        val asked = ArrayList<ChangeRequest>()
        val support = ConfirmationSupport(policy = { ConfirmationPolicy(global = mode) }, persistOff = { true }, stamp = { "s" })
        fun kernel(tool: AgentTool, a: JsonObject): ToolOutput = runBlocking {
            authorizeCall(support, mapOf(tool.spec.name to tool), ToolCall("id", tool.spec.name, a),
                wait = { request, _ -> asked += request; answer }, onNotSaved = {}, io = Dispatchers.Unconfined) ?: tool.execute(a)
        }
    }

    // ---- texts and catalog ----

    @Test fun `the approved descriptions, schemas and flags`() {
        val f = TunnelFixture()
        val start = StartTunnelTool(f.tunnels); val stop = StopTunnelTool(f.tunnels)
        assertEquals("Start one saved tunnel from profile_information (profile_id, connection_id), as the owner's Start button does, and wait until the device takes connections through it. Changes only this app, nothing on the device.", start.spec.description)
        assertEquals("Stop one saved tunnel (profile_id, connection_id), as the owner's Stop button does. The owner's terminal and file tabs on it close; tmux sessions on the device stay. The owner may be asked before it runs.", stop.spec.description)
        for (tool in listOf(start, stop)) {
            assertEquals(listOf("profile_id", "connection_id"), tool.spec.parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(setOf("profile_id", "connection_id"), tool.spec.parameters.getValue("properties").jsonObject.keys)
        }
        // One switch, default off: the start tool owns it, the stop tool follows it; start runs without a question, stop asks.
        assertEquals("assistant_tunnels", AssistantToolId.START_TUNNEL.settingKey)
        assertEquals("assistant_tunnels", AssistantToolId.STOP_TUNNEL.settingKey)
        assertTrue(AssistantToolId.START_TUNNEL.ownsSwitch); assertFalse(AssistantToolId.STOP_TUNNEL.ownsSwitch)
        assertFalse(AssistantToolId.START_TUNNEL.confirms); assertTrue(AssistantToolId.STOP_TUNNEL.confirms)
        assertFalse(AssistantTools().tunnels)
    }

    // ---- start_tunnel ----

    @Test fun `start brings the tunnel up as the owner's Start does and says so`() {
        val f = TunnelFixture(); f.host.add(name = "pi ssh", device = "wb7", port = 22)
        assertEquals(ToolOutput("Tunnel pi ssh is on. The device takes connections."), start(f))
        assertEquals(listOf(ru.adsrv.frpjump.ops.tunnel.TunnelUpInput("p1", "wb7", 22, PortKind.SSH)), f.up.calls)
        // One already on.
        val on = TunnelFixture(); on.host.add(isOn = true)
        assertEquals(ToolOutput("Tunnel pi ssh was already on."), start(on))
    }

    @Test fun `start words a tunnel that is on but not taking connections and one that did not come up`() {
        val f = TunnelFixture(up = FakeUp(FakeUp.notListening())); f.host.add()
        assertEquals(ToolOutput("Tunnel pi ssh is on, but the device does not take connections yet.", ToolOutcome.FAILED), start(f))
        val g = TunnelFixture(up = FakeUp(FakeUp.didNotComeUp())); g.host.add()
        // Proposed wording (not in the approved list): pinned here so a change is seen.
        assertEquals(ToolOutput("Tunnel pi ssh did not come up. Nothing else was changed.", ToolOutcome.FAILED), start(g))
    }

    @Test fun `start refuses unknown ids and bad arguments and asks the operation nothing`() {
        val f = TunnelFixture(); f.host.add()
        val provide = ToolOutput("Provide profile_id and connection_id from profile_information.", ToolOutcome.FAILED)
        assertEquals(provide, start(f, args(profile = "other")))
        assertEquals(provide, start(f, args(connection = "other")))
        assertEquals(provide, start(f, buildJsonObject { put("profile_id", "p1") }))
        assertEquals(provide, start(f, buildJsonObject { put("profile_id", "p1"); put("connection_id", "c1"); put("x", "y") }))
        assertTrue(f.up.calls.isEmpty())
    }

    @Test fun `start never asks, with the question on, and runs`() {
        val f = TunnelFixture(); f.host.add()
        val asker = Asker(ConfirmationDecision.DENY)
        val out = asker.kernel(StartTunnelTool(f.tunnels), args())
        assertTrue(asker.asked.isEmpty())
        assertEquals("Tunnel pi ssh is on. The device takes connections.", out.text)
        assertEquals(1, f.up.calls.size)
    }

    // ---- stop_tunnel ----

    @Test fun `stop asks, shows the tunnel and its tabs, and stops at once only after Allow`() {
        for (answer in listOf(ConfirmationDecision.ONCE, ConfirmationDecision.SESSION, ConfirmationDecision.ALWAYS)) {
            val f = TunnelFixture(); f.host.add(isOn = true); f.host.tabCount = 2
            val asker = Asker(answer)
            val out = asker.kernel(StopTunnelTool(f.tunnels), args())
            assertEquals(answer.name, ToolOutput("Tunnel pi ssh is off. Its tabs closed; tmux sessions on the device stay."), out)
            val request = asker.asked.single()
            assertEquals(AssistantToolId.STOP_TUNNEL, request.tool)
            assertEquals(GrantTarget("p1", "c1"), request.target)
            val card = request.stop!!
            assertEquals(listOf("Home", "pi ssh", "wb7", 22, 2), listOf(card.profile, card.name, card.device, card.port, card.tabs))
            // The tunnel's one tunnel, at once: no Undo target.
            assertEquals(listOf<TunnelDownTarget>(TunnelDownTarget.OneNow(TunnelRef("p1", "wb7", 22))), f.down.targets)
        }
    }

    @Test fun `stop declined or unanswered stops nothing`() {
        for (answer in listOf(ConfirmationDecision.DENY, ConfirmationDecision.NO_ANSWER)) {
            val f = TunnelFixture(); f.host.add(isOn = true)
            val out = Asker(answer).kernel(StopTunnelTool(f.tunnels), args())
            assertEquals(ToolOutcome.DECLINED, out.outcome)
            assertTrue(answer.name, f.down.targets.isEmpty())
        }
    }

    @Test fun `stop runs at once, like other changing commands, when the question is off`() {
        val f = TunnelFixture(); f.host.add(isOn = true)
        val asker = Asker(ConfirmationDecision.DENY, mode = ConfirmationMode.OFF)
        val out = asker.kernel(StopTunnelTool(f.tunnels), args())
        assertTrue(asker.asked.isEmpty())
        assertEquals("Tunnel pi ssh is off. Its tabs closed; tmux sessions on the device stay.", out.text)
        assertEquals(1, f.down.targets.size)
    }

    @Test fun `the card has no tab count to show for none`() {
        val f = TunnelFixture(); f.host.add(isOn = true); f.host.tabCount = 0
        val asker = Asker(); asker.kernel(StopTunnelTool(f.tunnels), args())
        assertEquals(0, asker.asked.single().stop!!.tabs)
    }

    @Test fun `stop acts only on the tunnel that was described`() {
        // Edited between the card and the run.
        val edited = TunnelFixture(); val ids = edited.host.add(isOn = true)
        var asked = false
        val tool = StopTunnelTool(edited.tunnels)
        val out = runBlocking {
            authorizeCall(Asker().support, mapOf("stop_tunnel" to tool), ToolCall("id", "stop_tunnel", args()),
                wait = { _, _ -> edited.host.versions[ids] = 1; asked = true; ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined) ?: tool.execute(args())
        }
        assertTrue(asked)
        assertEquals(ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED), out)
        assertTrue(edited.down.targets.isEmpty())
        // Removed.
        val gone = TunnelFixture(); val gid = gone.host.add(isOn = true)
        val goneTool = StopTunnelTool(gone.tunnels)
        val goneOut = runBlocking {
            authorizeCall(Asker().support, mapOf("stop_tunnel" to goneTool), ToolCall("id", "stop_tunnel", args()),
                wait = { _, _ -> gone.host.saved.remove(gid); ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined) ?: goneTool.execute(args())
        }
        assertEquals(ToolOutput("Provide profile_id and connection_id from profile_information.", ToolOutcome.FAILED), goneOut)
        assertTrue(gone.down.targets.isEmpty())
        // Stopped by someone else (the owner's own Stop) since it was described: no longer the tunnel that was described.
        val other = TunnelFixture(); val oid = other.host.add(isOn = true)
        val otherTool = StopTunnelTool(other.tunnels)
        val otherOut = runBlocking {
            authorizeCall(Asker().support, mapOf("stop_tunnel" to otherTool), ToolCall("id", "stop_tunnel", args()),
                wait = { _, _ -> other.host.on.remove(oid); ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined) ?: otherTool.execute(args())
        }
        assertEquals(ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED), otherOut)
        assertTrue(other.down.targets.isEmpty())
    }

    @Test fun `a call that was never described stops nothing`() {
        val f = TunnelFixture(); f.host.add(isOn = true)
        // Straight to execute, with no description before it: nothing was shown to the owner.
        assertEquals(ToolOutput(NOT_AUTHORIZED, ToolOutcome.FAILED), runBlocking { StopTunnelTool(f.tunnels).execute(args()) })
        assertTrue(f.down.targets.isEmpty())
    }

    @Test fun `a tunnel that was not on is not stopped`() {
        val f = TunnelFixture(); f.host.add(isOn = false)
        val out = Asker().kernel(StopTunnelTool(f.tunnels), args())
        assertEquals(ToolOutput("Tunnel pi ssh was not on. Nothing was stopped.", ToolOutcome.FAILED), out)
        assertTrue(f.down.targets.isEmpty())
    }

    @Test fun `stop with unknown ids asks nothing and says what to provide`() {
        val f = TunnelFixture(); f.host.add(isOn = true)
        val asker = Asker()
        val out = asker.kernel(StopTunnelTool(f.tunnels), args(connection = "other"))
        assertTrue(asker.asked.isEmpty())
        assertEquals(ToolOutcome.FAILED, out.outcome)
        assertTrue(f.down.targets.isEmpty())
        assertEquals(ToolOutput("Provide profile_id and connection_id from profile_information.", ToolOutcome.FAILED),
            runBlocking { StopTunnelTool(f.tunnels).execute(buildJsonObject { put("profile_id", "p1") }) })
    }

    @Test fun `a name with control characters reaches the model cleaned`() {
        val f = TunnelFixture(); f.host.add(name = "a\nb‮c", isOn = true)
        val out = Asker().kernel(StopTunnelTool(f.tunnels), args())
        assertEquals("Tunnel a bc is off. Its tabs closed; tmux sessions on the device stay.", out.text)
    }

    // ---- the switch ----

    @Test fun `with the switch off neither tool is offered, and request_tool lists the switch`() {
        val f = TunnelFixture()
        fun names(tools: AssistantTools) = AssistantToolFactory.create(tools, ru.adsrv.frpjump.assistant.AssistantChatRuntime(), AgentProvider { AgentReply("x") }, null, null, { },
            emptySet(), null, f.tunnels).map { it.spec.name }
        assertTrue(names(AssistantTools()).none { it == "start_tunnel" || it == "stop_tunnel" })
        assertEquals(listOf("start_tunnel", "stop_tunnel"), names(AssistantTools(tunnels = true)).filter { it.endsWith("_tunnel") })
        // Off: one entry for both tools, by the line the operator approved.
        val request = AssistantToolFactory.create(AssistantTools(), ru.adsrv.frpjump.assistant.AssistantChatRuntime(), AgentProvider { AgentReply("x") }, null, null, { }, emptySet(), null, f.tunnels)
            .last { it.spec.name == "request_tool" }
        assertTrue(request.spec.description, "start_tunnel - start and stop the owner's saved tunnels" in request.spec.description)
        assertFalse("stop_tunnel" in request.spec.description)
        // No owner of tunnels: the switch adds nothing.
        assertTrue(AssistantToolFactory.create(AssistantTools(tunnels = true), ru.adsrv.frpjump.assistant.AssistantChatRuntime(), AgentProvider { AgentReply("x") }, null, null, { }, emptySet(), null, null)
            .none { it.spec.name.endsWith("_tunnel") })
        assertNull(AssistantToolId.fromWire("start_tunnel")?.let { if (AssistantTools().get(it)) it else null })
    }
}
