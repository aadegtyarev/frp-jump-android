package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.ConfirmationTargetInfo
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.ssh.SshExec
import ru.adsrv.frpjump.ops.ssh.SshExecInput
import ru.adsrv.frpjump.ops.ssh.SshExecOutput
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ssh.SshTargets

/** The saved SSH targets of the assistant: the operation, and the pure lookups the confirmation card needs. */
open class AssistantSshCommands(private val targets: SshTargets) {
    /** Names for the confirmation card; a pure lookup of saved state: no client, no tunnel, no network. Null when unknown. */
    internal open fun info(target: GrantTarget): ConfirmationTargetInfo? = targets.labels(target.profileId, target.connectionId)
        ?.let { ConfirmationTargetInfo(it.first, it.second) }
    /** Changes when the saved connection or its profile is edited or removed (a session grant must not follow it). */
    internal open fun stamp(target: GrantTarget): Any? = targets.stamp(target.profileId, target.connectionId)

    internal open val exec: Operation<SshExecInput, SshExecOutput> by lazy { SshExec(targets) }
}

internal class AssistantSshCommandTool(commands: AssistantSshCommands) : OperationTool<SshExecInput, SshExecOutput>(
    AssistantToolId.SSH_COMMAND,
    "Run one noninteractive command on a saved SSH connection over a ready tunnel. Use profile_information IDs. FULL SSH ACCESS within account rights; command/output may contain secrets and go to the model. No retries.",
    objectSchema { string("profile_id"); string("connection_id"); string("command") },
    commands.exec,
) {
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id", "command")
        SshExecInput(string("profile_id"), string("connection_id"), string("command"))
    }
    override val invalidArguments = ToolOutput("Provide profile_id, connection_id and one nonempty command only.", ToolOutcome.FAILED)
    override fun commandText(input: SshExecInput) = input.command
    override fun render(input: SshExecInput, output: SshExecOutput) =
        "stdout:\n${output.stdout}\nstderr:\n${output.stderr}\n${exitText(SshExit(output.status, output.signal))}"
    override fun outcome(output: SshExecOutput) = if (output.succeeded) ToolOutcome.SUCCESS else ToolOutcome.FAILED
    override val unexpected: OpFailure = SshFailure.Unexpected

    override fun modelText(refusal: OpRefusal) = when (refusal as SshRefusal) {
        SshRefusal.TargetUnavailable -> "SSH target unavailable. Use an existing ready saved SSH connection; no tunnel was started."
        SshRefusal.NeedsSetup -> "SSH login or host trust needs setup. Open this connection in the ordinary SSH UI first. No command was executed."
        SshRefusal.TargetLostBeforeExec -> "SSH target unavailable. No command was executed. Check the connection in the app."
        SshRefusal.ConnectFailed -> "SSH unavailable. Check the connection in the ordinary SSH UI. No command was executed."
    }

    override fun modelText(failure: OpFailure): String {
        val ssh = failure as SshFailure
        return when (ssh) {
            is SshFailure.TargetLost -> ssh.exit?.let { "SSH target became unavailable. Remote exit received; output withheld.\n${exitText(it)}" }
                ?: "SSH target became unavailable; remote outcome unknown. Check the connection in the app; do not automatically repeat the command."
            is SshFailure.CommandFailed -> ssh.exit?.let { "SSH command failed. Remote exit received; output withheld.\n${exitText(it)}" }
                ?: "SSH command failed; remote outcome unknown. Do not automatically repeat it."
            SshFailure.Unexpected -> "SSH failed; remote outcome unknown. Do not automatically repeat it."
        }
    }

    private fun exitText(exit: SshExit) = "exit_status: ${exit.status ?: "unknown"}\nexit_signal: ${exit.signal ?: "unknown"}"
}
