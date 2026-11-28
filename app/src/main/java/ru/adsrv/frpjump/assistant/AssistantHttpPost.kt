package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.HttpPostCard
import ru.adsrv.frpjump.assistant.tools.AppTool
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.assistant.tools.InvalidArguments
import ru.adsrv.frpjump.assistant.tools.StrictArgs
import ru.adsrv.frpjump.assistant.tools.objectSchema
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.settings.GrantTarget

/** One exact saved Web endpoint and literal body, pinned before a possible confirmation. */
internal class AssistantHttpPostTool(private val http: AssistantWebHttp, private val budget: Int) : AppTool<AssistantHttpPostTool.Input>(AssistantToolId.HTTP_POST,
    "Send one owner-requested HTTP POST through an already running saved Web tunnel. Use profile_id and connection_id from profile_information, a root-relative path, content type and literal UTF-8 body. May change the device; no retry.",
    objectSchema { string("profile_id", 256); string("connection_id", 256); string("path", 2048); enum("content_type", listOf("application/json", "text/plain")); string("body", 8192) }) {
    data class Input(val ids: SavedTunnelIds, val path: String, val contentType: String, val body: String) {
        override fun toString() = "Input(POST)"
    }
    private var prepared: Pair<Input, WebHttpTarget>? = null
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id", "path", "content_type", "body")
        val ids = SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
        val path = string("path", 2048)
        val type = string("content_type", 32)
        val body = string("body", 8192, allowBlank = true)
        if (!exactHttpPath(path) || !Charsets.UTF_8.newEncoder().canEncode(body) || type !in setOf("application/json", "text/plain") || body.toByteArray(Charsets.UTF_8).size > 8192) throw InvalidArguments()
        Input(ids, path, type, body)
    }
    override val invalidArguments = ToolOutput("Provide saved Web profile_id and connection_id, a root-relative path, application/json or text/plain and a UTF-8 body up to 8192 bytes.", ToolOutcome.FAILED)
    override fun asksAbout(args: Input): Boolean {
        prepared = http.pin(args.ids)?.takeIf { it.profile.isNotBlank() && it.device.isNotBlank() && it.port in 1..65535 && (!it.https || it.certReadable) }?.let { args to it }
        return prepared != null
    }
    override fun describeChange(args: Input): ChangeRequest {
        val target = checkNotNull(prepared).second
        return ChangeRequest(id, GrantTarget(args.ids.profileId, args.ids.connectionId), args.body,
            http = HttpPostCard(target.profile, target.name, target.device, target.port, target.https, args.path, args.contentType))
    }
    override suspend fun run(args: Input): ToolOutput {
        val pin = prepared.also { prepared = null } ?: return unavailable()
        if (pin.first != args) return unavailable()
        return http.post(args.ids, args.path, args.contentType, args.body, budget, pin.second)
    }
    override fun failure(error: Exception) = ToolOutput("The POST outcome is unknown. Do not retry automatically.", ToolOutcome.FAILED)
    private fun unavailable() = ToolOutput("The saved Web tunnel is unavailable or changed. Nothing was sent.", ToolOutcome.FAILED)
}
