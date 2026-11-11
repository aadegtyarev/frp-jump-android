package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.ProfilesState
import ru.adsrv.frpjump.ssh.*
import ru.adsrv.frpjump.tunnel.*
import ru.adsrv.frpjump.undo.PendingUndo
import java.io.InputStream

/** One saved target, one ordinary owned client. Never uses Terminal/Files clients or starts tunnels. */
class AssistantSshCommands(
    private val profiles: StateFlow<ProfilesState>,
    private val hidden: StateFlow<Set<String>>,
    private val env: SshEnvironment,
) {
    internal suspend fun execute(profile: String, connection: String, command: String, dispatch: WebDispatch): ToolOutput = coroutineScope {
        val state = profiles.value
        val runtime = state.runtimes[profile] ?: return@coroutineScope unavailable()
        if (!state.loaded || state.index.profiles.count { it.id == profile } != 1) return@coroutineScope unavailable()
        val saved = runtime.connections.connections.value.filter { it.id == connection }.singleOrNull()
            ?.takeIf { it.kind == PortKind.SSH } ?: return@coroutineScope unavailable()
        val tunnel = runtime.tunnels.state.value.tunnels.filter { it.key == ru.adsrv.frpjump.ports.localPortKey(saved.device, saved.port) }
            .singleOrNull() ?: return@coroutineScope unavailable()
        val enrollment = state.enrollments[profile]
        fun available(): Boolean {
            val now = profiles.value
            val t = runtime.tunnels.state.value
            return now.loaded && now.index.profiles.singleOrNull { it.id == profile }?.activationPending == false &&
                now.runtimes[profile] === runtime && now.enrollments[profile] == enrollment &&
                !runtime.session.removed.value && runtime.connections.connections.value.count { it.id == connection } == 1 &&
                runtime.connections.connections.value.singleOrNull { it.id == connection } == saved &&
                PendingUndo.connectionKey(profile, connection) !in hidden.value &&
                PendingUndo.tunnelKey(profile, saved.device, saved.port) !in hidden.value &&
                t.pending[tunnel.key] == null && t.tunnels.any { sameCommandTunnel(it, tunnel) && commandTunnelReady(it) }
        }
        if (!available()) return@coroutineScope unavailable()
        // Observers only signal cancellation; no transport cleanup or suspension in a source publisher.
        val operation = SshCommandOperation()
        val watchers = listOf(
            launch(Dispatchers.Unconfined) { profiles.collect { if (!available()) operation.loseTarget() } },
            launch(Dispatchers.Unconfined) { hidden.collect { if (!available()) operation.loseTarget() } },
            launch(Dispatchers.Unconfined) { runtime.connections.connections.collect { if (!available()) operation.loseTarget() } },
            launch(Dispatchers.Unconfined) { runtime.tunnels.state.collect { if (!available()) operation.loseTarget() } },
            launch(Dispatchers.Unconfined) { runtime.session.removed.collect { if (it) operation.loseTarget() } },
            launch(Dispatchers.Unconfined) { runtime.tunnels.commandTargetPresent(tunnel).collect { if (!it) operation.loseTarget() } },
        )
        try {
            operation.run(dispatch) {
                if (!available()) return@run unavailable()
                val login = runtime.sshLogins.stored(saved.device, saved.port)?.copy(user = saved.sshUser)
                    ?: return@run setup()
                if (!connectsDirectly(login, env.passwords.has(profile, saved.device, saved.port, login.user),
                        env.refusals.get(profile, saved.device, saved.port, login.user), login.user)) return@run setup()
                val auth = if (login.method == SshAuthMethod.KEY) SshAuth.Key else SshAuth.Password(null, true)
                val connector = SshConnector(profile, runtime.hostKeys, saved.device, saved.port, tunnel.localPort, login.user, auth, env)
                operation.execute(connector, command, ::available)
            }
        } finally { watchers.forEach { it.cancel() } }
    }
}

internal fun sameCommandTunnel(a: Tunnel, b: Tunnel) = a.key == b.key && a.grantId == b.grantId && a.localPort == b.localPort
internal fun commandTunnelReady(t: Tunnel) = t.state.status in setOf(TunnelStatus.DIRECT, TunnelStatus.RELAYED, TunnelStatus.TRYING) &&
    !t.state.waitingForPeer && !t.state.waitingForDevice && !t.state.deviceMissing
private fun unavailable() = ToolOutput("SSH target unavailable. Use an existing ready saved SSH connection; no tunnel was started.", ToolOutcome.FAILED)
private fun setup() = ToolOutput("SSH login or host trust needs setup. Open this connection in the ordinary SSH UI first. No command was executed.", ToolOutcome.FAILED)

internal class AssistantSshCommandTool(private val commands: AssistantSshCommands, private val dispatch: WebDispatch) : AppTool<SshCommandArgs>(
    AssistantToolId.SSH_COMMAND,
    "Run one noninteractive command on a saved SSH connection over a ready tunnel. Use profile_information IDs. FULL SSH ACCESS within account rights; command/output may contain secrets and go to the model. No retries.",
    objectSchema { string("profile_id"); string("connection_id"); string("command") },
) {
    override fun parse(args: StrictArgs) = args.run {
        exactKeys("profile_id", "connection_id", "command")
        SshCommandArgs(string("profile_id"), string("connection_id"), string("command"))
    }
    override val invalidArguments = ToolOutput("Provide profile_id, connection_id and one nonempty command only.", ToolOutcome.FAILED)
    override suspend fun run(args: SshCommandArgs) = commands.execute(args.profile, args.connection, args.command, dispatch)
    override fun failure(error: Exception) = ToolOutput("SSH failed; remote outcome unknown. Do not automatically repeat it.", ToolOutcome.FAILED)
}
/** Not a data class: its toString must never print the command. */
internal class SshCommandArgs(val profile: String, val connection: String, val command: String)

/** The coroutine cannot finish until the admitted worker, readers and disconnect have really exited. */
internal class SshCommandOperation {
    private val lock = Any()
    private var cancelled = false
    private var targetLost = false
    private var execAdmitted = false
    private var command: Session.Command? = null
    private var client: SSHClient? = null
    private val closeRequested = CompletableDeferred<Unit>()

    fun cancel() {
        synchronized(lock) { cancelled = true }
        closeRequested.complete(Unit)
    }
    /** Target loss stops resources, but is a tool failure, not revocation of the caller's Send. */
    fun loseTarget() {
        synchronized(lock) { targetLost = true }
        closeRequested.complete(Unit)
    }
    private fun checkActive() = synchronized(lock) {
        if (targetLost || cancelled) throw CancellationException()
    }
    /** SSHJ caches these nullable facts independently of stream consumption, including after channel close. */
    private fun receivedExit(): String? {
        val running = synchronized(lock) { command } ?: return null
        val status = running.exitStatus
        val signal = running.exitSignal
        return if (status == null && signal == null) null else
            "exit_status: ${status ?: "unknown"}\nexit_signal: ${signal?.name ?: "unknown"}"
    }
    private fun targetFailure(): ToolOutput {
        val received = receivedExit()
        return ToolOutput(when {
            !execAdmitted -> "SSH target unavailable. No command was executed. Check the connection in the app."
            received != null -> "SSH target became unavailable. Remote exit received; output withheld.\n$received"
            else -> "SSH target became unavailable; remote outcome unknown. Check the connection in the app; do not automatically repeat the command."
        }, ToolOutcome.FAILED)
    }

    suspend fun run(dispatch: WebDispatch, work: suspend () -> ToolOutput): ToolOutput = coroutineScope {
        // Independent worker: caller cancellation signals resources, but must not pretend the worker exited.
        val workers = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val closer = workers.launch { closeRequested.await(); synchronized(lock) { client }?.let { runCatching { it.disconnect() } } }
        val worker = workers.async(start = CoroutineStart.LAZY) { checkActive(); work() }
        var result: ToolOutput? = null
        try {
            dispatch("ssh-dispatch") { checkActive(); worker.start() }
            result = worker.await()
        } catch (failure: Exception) {
            // Target loss can also make construction/reads fail; only an active caller gets the local failure below.
            currentCoroutineContext().ensureActive()
            if (!synchronized(lock) { targetLost }) throw failure
        } finally {
            this@SshCommandOperation.cancel()
            withContext(NonCancellable) {
                // Includes a client constructed after cancellation and its final disconnect.
                worker.cancel(); worker.join(); closer.join()
            }
            workers.cancel()
        }
        // Stop/timeout/owner revocation wins even when it races a local target failure.
        currentCoroutineContext().ensureActive()
        synchronized(lock) { if (targetLost) targetFailure() else checkNotNull(result) }
    }

    suspend fun execute(connector: SshConnector, command: String, available: () -> Boolean): ToolOutput {
        val ssh = connector.newClient()
        synchronized(lock) { client = ssh }
        var session: Session? = null
        try {
            checkActive()
            if (!available()) return unavailable()
            connector.connect(ssh); checkActive()
            connector.authenticate(ssh); checkActive()
            if (!available()) return unavailable()
            session = ssh.startSession(); checkActive()
            if (!available()) return unavailable()
            // After this point a failure may have uncertain remote effects.
            synchronized(lock) { execAdmitted = true }
            val running = session.exec(command).also { synchronized(lock) { this.command = it } }
            running.outputStream.close()
            return coroutineScope {
                val stdout = async(Dispatchers.IO) { captureSshStream(running.inputStream) }
                val stderr = async(Dispatchers.IO) { captureSshStream(running.errorStream) }
                try {
                    val out = stdout.await(); val err = stderr.await()
                    running.join()
                    checkActive()
                    val status = running.exitStatus
                    val signal = running.exitSignal
                    ToolOutput("stdout:\n$out\nstderr:\n$err\nexit_status: ${status ?: "unknown"}\nexit_signal: ${signal?.name ?: "unknown"}",
                        if (status == 0 && signal == null) ToolOutcome.SUCCESS else ToolOutcome.FAILED)
                } catch (failure: Exception) {
                    // Unblock the sibling stream before structured scope waits for it.
                    runCatching { ssh.disconnect() }
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: HostKeyRejected) { return setup() }
        catch (_: net.schmizz.sshj.userauth.UserAuthException) { return setup() }
        catch (_: Exception) {
            val received = receivedExit()
            return ToolOutput(when {
                received != null -> "SSH command failed. Remote exit received; output withheld.\n$received"
                synchronized(lock) { execAdmitted } -> "SSH command failed; remote outcome unknown. Do not automatically repeat it."
                else -> "SSH unavailable. Check the connection in the ordinary SSH UI. No command was executed."
            }, ToolOutcome.FAILED)
        } finally {
            runCatching { session?.close() }
            runCatching { ssh.disconnect() }
        }
    }
}

/** Retain at most 3,000 UTF-16 characters per stream; keep draining excess to avoid channel deadlock. */
internal fun captureSshStream(stream: InputStream): String {
    val text = StringBuilder()
    var omitted = false
    val reader = stream.reader(Charsets.UTF_8)
    val buffer = CharArray(2048)
    while (true) {
        val n = reader.read(buffer)
        if (n < 0) break
        val keep = minOf(n, 3000 - text.length)
        if (keep > 0) text.append(buffer, 0, keep)
        if (keep < n) omitted = true
    }
    if (omitted) {
        if (text.isNotEmpty() && text.last().isHighSurrogate()) text.setLength(text.length - 1)
        text.append("\n[… output omitted …]")
    }
    return text.toString()
}
