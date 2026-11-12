package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ops.profile.ProfileInformationQuery
import ru.adsrv.frpjump.ops.ssh.SshExec
import ru.adsrv.frpjump.ops.ssh.SshExecInput
import ru.adsrv.frpjump.ops.ssh.SshExecOutput
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.ProfileInformationState
import ru.adsrv.frpjump.profiles.SyntheticProfileInformation
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ssh.*

/**
 * The assistant's adapter over operations: the effect and target the confirmation uses come from the operation, and every typed result has
 * exactly one model text and outcome. The texts here are the golden ones the model has always been given.
 */
class OperationToolTest {
    private val synthetic = SyntheticProfileInformation()
    private val env: SshEnvironment get() {
        val box = FakeKeystore().box("synthetic-ssh")
        return SshEnvironment(SshKeyStore(MemoryStorage(), box), SshPasswords(MemoryStorage(), box), { "synthetic" }, TmuxNames(MemoryStorage()),
            clientFactory = { throw AssertionError("must not construct a client") })
    }
    private fun targets() = SshTargets(synthetic.state, synthetic.hidden, env)
    private val sshArgs = buildJsonObject { put("profile_id", "p"); put("connection_id", "c"); put("command", "secret-command") }

    /** A scripted operation behind the production tool. */
    private class Scripted(val effectOf: Effect = Effect.CHANGE_REMOTE, val result: suspend () -> OpResult<SshExecOutput>) : Operation<SshExecInput, SshExecOutput> {
        override val name = "scripted"
        override val effect = effectOf
        override fun target(input: SshExecInput) = GrantTarget(input.profile, input.connection)
        override suspend fun invoke(input: SshExecInput) = result()
    }
    private fun sshTool(result: suspend () -> OpResult<SshExecOutput>) =
        AssistantSshCommandTool(object : AssistantSshCommands(targets()) { override val exec = Scripted(result = result) })

    private fun run(result: OpResult<SshExecOutput>) = runBlocking { sshTool { result }.execute(sshArgs) }

    @Test fun `every operation tool takes its effect from its operation and it equals the catalog's`() {
        val ssh = AssistantSshCommandTool(AssistantSshCommands(targets()))
        val profile = AssistantProfileInformationTool(synthetic.information)
        val operations = mapOf<AssistantToolId, Pair<OperationTool<*, *>, Operation<*, *>>>(
            AssistantToolId.SSH_COMMAND to (ssh to SshExec(targets())),
            AssistantToolId.PROFILE_INFORMATION to (profile to ru.adsrv.frpjump.ops.profile.ProfileQuery({ }, { ProfileInformationState() })),
        )
        for ((id, pair) in operations) {
            assertEquals("$id tool", id.effect, pair.first.effect)
            assertEquals("$id operation", id.effect, pair.second.effect)
            assertSame(id, pair.first.id)
        }
        // The two pilots are the whole set; a later operation tool must be listed here.
        assertEquals(setOf(AssistantToolId.SSH_COMMAND, AssistantToolId.PROFILE_INFORMATION), operations.keys)
    }

    @Test fun `the confirmation takes its effect and target from the operation`() {
        val request = (sshTool { OpResult.Done(SshExecOutput("", "", 0, null)) }.changeCheck(sshArgs) as ChangeCheck.Change).request
        assertEquals(GrantTarget("p", "c"), request.target)
        assertEquals("secret-command", request.command)
        assertTrue(request.described)
        assertSame(AssistantToolId.SSH_COMMAND, request.tool)
        // An operation that only reads is never asked about, whatever the catalog says for the tool.
        val reading = AssistantSshCommandTool(object : AssistantSshCommands(targets()) { override val exec = Scripted(Effect.READ_REMOTE) { error("not run") } })
        assertSame(ChangeCheck.ReadOnly, reading.changeCheck(sshArgs))
    }

    @Test fun `SshExec declares its name effect and target`() {
        val exec = SshExec(targets())
        assertEquals("ssh_exec", exec.name)
        assertEquals(Effect.CHANGE_REMOTE, exec.effect)
        assertEquals(GrantTarget("p", "c"), exec.target(SshExecInput("p", "c", "x")))
    }

    @Test fun `a finished command gives its streams and exit, and only a clean exit is a success`() {
        assertEquals(ToolOutput("stdout:\nout\nstderr:\nerr\nexit_status: 0\nexit_signal: unknown", ToolOutcome.SUCCESS), run(OpResult.Done(SshExecOutput("out", "err", 0, null))))
        assertEquals(ToolOutput("stdout:\n\nstderr:\n\nexit_status: 7\nexit_signal: unknown", ToolOutcome.FAILED), run(OpResult.Done(SshExecOutput("", "", 7, null))))
        assertEquals(ToolOutput("stdout:\n\nstderr:\n\nexit_status: unknown\nexit_signal: TERM", ToolOutcome.FAILED), run(OpResult.Done(SshExecOutput("", "", null, "TERM"))))
        assertEquals(ToolOutput("stdout:\n\nstderr:\n\nexit_status: 0\nexit_signal: KILL", ToolOutcome.FAILED), run(OpResult.Done(SshExecOutput("", "", 0, "KILL"))))
        assertEquals(ToolOutput("stdout:\n\nstderr:\n\nexit_status: unknown\nexit_signal: unknown", ToolOutcome.FAILED), run(OpResult.Done(SshExecOutput("", "", null, null))))
    }

    @Test fun `each ssh refusal has its own fixed text and a failed outcome`() {
        val texts = mapOf(
            SshRefusal.TargetUnavailable to "SSH target unavailable. Use an existing ready saved SSH connection; no tunnel was started.",
            SshRefusal.NeedsSetup to "SSH login or host trust needs setup. Open this connection in the ordinary SSH UI first. No command was executed.",
            SshRefusal.TargetLostBeforeExec to "SSH target unavailable. No command was executed. Check the connection in the app.",
            SshRefusal.ConnectFailed to "SSH unavailable. Check the connection in the ordinary SSH UI. No command was executed.",
        )
        for ((refusal, text) in texts) assertEquals("$refusal", ToolOutput(text, ToolOutcome.FAILED), run(OpResult.Refused(refusal)))
        assertEquals(4, texts.values.toSet().size)
    }

    @Test fun `each ssh failure has its own fixed text, with the exit it already reported or an unknown outcome`() {
        val exit = SshExit(3, null); val signal = SshExit(null, "HUP")
        assertEquals(ToolOutput("SSH target became unavailable. Remote exit received; output withheld.\nexit_status: 3\nexit_signal: unknown", ToolOutcome.FAILED),
            run(OpResult.Failed(SshFailure.TargetLost(exit), RemoteOutcome.KNOWN)))
        assertEquals(ToolOutput("SSH target became unavailable. Remote exit received; output withheld.\nexit_status: unknown\nexit_signal: HUP", ToolOutcome.FAILED),
            run(OpResult.Failed(SshFailure.TargetLost(signal), RemoteOutcome.KNOWN)))
        assertEquals(ToolOutput("SSH target became unavailable; remote outcome unknown. Check the connection in the app; do not automatically repeat the command.", ToolOutcome.FAILED),
            run(OpResult.Failed(SshFailure.TargetLost(null), RemoteOutcome.UNKNOWN)))
        assertEquals(ToolOutput("SSH command failed. Remote exit received; output withheld.\nexit_status: 3\nexit_signal: unknown", ToolOutcome.FAILED),
            run(OpResult.Failed(SshFailure.CommandFailed(exit), RemoteOutcome.KNOWN)))
        assertEquals(ToolOutput("SSH command failed; remote outcome unknown. Do not automatically repeat it.", ToolOutcome.FAILED),
            run(OpResult.Failed(SshFailure.CommandFailed(null), RemoteOutcome.UNKNOWN)))
        assertEquals(ToolOutput("SSH failed; remote outcome unknown. Do not automatically repeat it.", ToolOutcome.FAILED),
            run(OpResult.Failed(SshFailure.Unexpected, RemoteOutcome.UNKNOWN)))
    }

    @Test fun `an unexpected exception of the operation is the fixed unknown-outcome text and never its message`() {
        val result = runBlocking { sshTool { error("secret-detail") }.execute(sshArgs) }
        assertEquals(ToolOutput("SSH failed; remote outcome unknown. Do not automatically repeat it.", ToolOutcome.FAILED), result)
    }

    @Test fun `cancellation of the operation propagates through the tool`() {
        try {
            runBlocking { sshTool { throw CancellationException("stop") }.execute(sshArgs) }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `profile information renders the query it was asked with and words its failure`() = runBlocking {
        val state = ProfileInformationState()
        val tool = AssistantProfileInformationTool({ }, { state })
        assertEquals(ToolOutput(profileInformationText(state, ProfileInformationQuery("zz", null, 3))),
            tool.execute(buildJsonObject { put("query", "zz"); put("offset", 3) }))
        val failed = AssistantProfileInformationTool({ error("private-detail") }, { state }).execute(buildJsonObject {})
        assertEquals(ToolOutput("Could not obtain profile information. No successful refresh is implied.", ToolOutcome.FAILED), failed)
        assertSame(ChangeCheck.ReadOnly, tool.changeCheck(buildJsonObject {}))
    }

    @Test fun `the profile query operation reports a failed refresh or read as a failure with an unknown outcome`() = runBlocking {
        val query = ru.adsrv.frpjump.ops.profile.ProfileQuery({ }, { ProfileInformationState() })
        assertTrue(query(ProfileInformationQuery()) is OpResult.Done)
        assertEquals(OpResult.Failed(ProfileQueryFailure.Unavailable, RemoteOutcome.UNKNOWN),
            ru.adsrv.frpjump.ops.profile.ProfileQuery({ error("x") }, { ProfileInformationState() })(ProfileInformationQuery()))
        assertEquals(OpResult.Failed(ProfileQueryFailure.Unavailable, RemoteOutcome.UNKNOWN),
            ru.adsrv.frpjump.ops.profile.ProfileQuery({ }, { error("x") })(ProfileInformationQuery()))
        assertEquals("profile_query", query.name); assertEquals(Effect.READ_REMOTE, query.effect)
    }

    @Test fun `an unavailable saved target is a refusal from the operation itself and starts nothing`() = runBlocking {
        val result = SshExec(targets())(SshExecInput("nobody", "nothing", "true"))
        assertEquals(OpResult.Refused(SshRefusal.TargetUnavailable), result)
    }
}
