package ru.adsrv.frpjump.ops.ssh

import kotlinx.coroutines.coroutineScope
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.SshRefusal
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ssh.SshTargetResolution
import ru.adsrv.frpjump.ssh.SshTargets

/** Not a data class: its toString must never print the command. */
class SshExecInput(val profile: String, val connection: String, val command: String)

/** What a command that ran to its end left: the captured streams and how it ended. A nonzero [status] is still a finished command. */
data class SshExecOutput(val stdout: String, val stderr: String, val status: Int?, val signal: String?) {
    val succeeded: Boolean get() = status == 0 && signal == null
}

/**
 * Runs one noninteractive command on a saved SSH connection whose tunnel is already ready: one saved target, one ordinary owned client.
 * Never uses Terminal/Files clients, never starts a tunnel, never asks a login question, never trusts a host key.
 */
class SshExec(private val targets: SshTargets) : Operation<SshExecInput, SshExecOutput> {
    override val name = "ssh_exec"
    override val effect = Effect.CHANGE_REMOTE
    override fun target(input: SshExecInput) = GrantTarget(input.profile, input.connection)

    override suspend fun invoke(input: SshExecInput): OpResult<SshExecOutput> = coroutineScope {
        val target = (targets.resolveSaved(input.profile, input.connection) as? SshTargetResolution.Ready)?.target
            ?: return@coroutineScope OpResult.Refused(SshRefusal.TargetUnavailable)
        // Observers only signal cancellation; no transport cleanup or suspension in a source publisher.
        val operation = SshCommandOperation()
        val watchers = targets.watchLost(target, this, operation::loseTarget)
        try {
            operation.run {
                if (!targets.isAvailable(target)) return@run OpResult.Refused(SshRefusal.TargetUnavailable)
                val connector = targets.connector(target) ?: return@run OpResult.Refused(SshRefusal.NeedsSetup)
                operation.execute(connector, input.command) { targets.isAvailable(target) }
            }
        } finally { watchers.forEach { it.cancel() } }
    }
}
