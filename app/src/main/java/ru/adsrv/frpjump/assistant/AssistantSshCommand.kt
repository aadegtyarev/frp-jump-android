package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.ssh.*

/** One saved target, one ordinary owned client. Never uses Terminal/Files clients or starts tunnels. */
class AssistantSshCommands(private val targets: SshTargets) {
    internal suspend fun execute(profile: String, connection: String, command: String): ToolOutput = coroutineScope {
        val target = (targets.resolveSaved(profile, connection) as? SshTargetResolution.Ready)?.target
            ?: return@coroutineScope unavailable()
        // Observers only signal cancellation; no transport cleanup or suspension in a source publisher.
        val operation = SshCommandOperation()
        val watchers = targets.watchLost(target, this, operation::loseTarget)
        try {
            operation.run {
                if (!targets.isAvailable(target)) return@run unavailable()
                val connector = targets.connector(target) ?: return@run setup()
                operation.execute(connector, command) { targets.isAvailable(target) }
            }
        } finally { watchers.forEach { it.cancel() } }
    }
}

internal fun unavailable() = ToolOutput("SSH target unavailable. Use an existing ready saved SSH connection; no tunnel was started.", ToolOutcome.FAILED)
internal fun setup() = ToolOutput("SSH login or host trust needs setup. Open this connection in the ordinary SSH UI first. No command was executed.", ToolOutcome.FAILED)

internal class AssistantSshCommandTool(private val commands: AssistantSshCommands) : AppTool<SshCommandArgs>(
    AssistantToolId.SSH_COMMAND,
    "Run one noninteractive command on a saved SSH connection over a ready tunnel. Use profile_information IDs. FULL SSH ACCESS within account rights; command/output may contain secrets and go to the model. No retries.",
    objectSchema { string("profile_id"); string("connection_id"); string("command") },
) {
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id", "command")
        SshCommandArgs(string("profile_id"), string("connection_id"), string("command"))
    }
    override val invalidArguments = ToolOutput("Provide profile_id, connection_id and one nonempty command only.", ToolOutcome.FAILED)
    override suspend fun run(args: SshCommandArgs) = commands.execute(args.profile, args.connection, args.command)
    override fun failure(error: Exception) = ToolOutput("SSH failed; remote outcome unknown. Do not automatically repeat it.", ToolOutcome.FAILED)
}
/** Not a data class: its toString must never print the command. */
internal class SshCommandArgs(val profile: String, val connection: String, val command: String)
