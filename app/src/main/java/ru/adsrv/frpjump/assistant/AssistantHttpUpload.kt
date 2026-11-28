package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.HttpPostCard
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.settings.GrantTarget

/** The exact workspace item and Web endpoint are pinned before authorization. */
internal class AssistantHttpUploadTool(private val http: AssistantWebHttp, private val budget: Int, private val lookup: (String) -> TempInfo?) :
    AppTool<AssistantHttpUploadTool.Input>(AssistantToolId.HTTP_UPLOAD,
        "Send one existing temporary file as binary HTTP POST through an already running saved Web tunnel. Use exact file_id and profile_id and connection_id from profile_information. May change the device; no retry. Returns only the HTTP status.",
        objectSchema { string("profile_id", 256); string("connection_id", 256); string("path", 2048); string("file_id", 64) }) {
    data class Input(val ids: SavedTunnelIds, val path: String, val fileId: String)
    private data class Prepared(val input: Input, val target: WebHttpTarget, val file: TempInfo)
    private var prepared: Prepared? = null
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id", "path", "file_id")
        val ids = SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
        val path = string("path", 2048); val file = string("file_id", 64)
        if (!exactHttpPath(path)) throw InvalidArguments()
        Input(ids, path, file)
    }
    override val invalidArguments = ToolOutput("Provide exact saved Web profile_id, connection_id, root-relative path and temporary file_id.", ToolOutcome.FAILED)
    override fun asksAbout(args: Input): Boolean {
        val file = lookup(args.fileId)
        val target = http.pin(args.ids)?.takeIf { it.profile.isNotBlank() && it.device.isNotBlank() && it.port in 1..65535 && (!it.https || it.certReadable) }
        prepared = if (file != null && target != null) Prepared(args, target, file) else null
        return prepared != null
    }
    override fun describeChange(args: Input): ChangeRequest {
        val pinned = checkNotNull(prepared); val target = pinned.target
        return ChangeRequest(id, GrantTarget(args.ids.profileId, args.ids.connectionId), "",
            http = HttpPostCard(target.profile, target.name, target.device, target.port, target.https, args.path, "application/octet-stream", pinned.file.name, pinned.file.bytes))
    }
    override suspend fun run(args: Input): ToolOutput {
        val pinned = prepared.also { prepared = null } ?: return unavailable()
        if (pinned.input != args) return unavailable()
        return http.upload(args.ids, args.path, pinned.file, budget, pinned.target)
    }
    override fun failure(error: Exception) = ToolOutput("The POST outcome is unknown. Do not retry automatically.", ToolOutcome.FAILED)
    private fun unavailable() = ToolOutput("The temporary file or saved Web tunnel is unavailable or changed. Nothing was sent.", ToolOutcome.FAILED)
}
