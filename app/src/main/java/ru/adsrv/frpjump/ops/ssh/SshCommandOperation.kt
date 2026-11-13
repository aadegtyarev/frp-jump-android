package ru.adsrv.frpjump.ops.ssh

import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ssh.*
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The coroutine cannot finish until the admitted worker, readers and every disconnect have really exited: the worker and
 * a closer are children of one scope. Stop, timeout, revocation and target loss all end in [disconnectClient] on an IO thread.
 * Only the start check (`ssh-dispatch`, [admitSideEffect]) takes the chat gate; client creation, credentials, connect,
 * authentication, exec, the stream reads and cleanup all run on IO outside it.
 */
internal class SshCommandOperation {
    private val lock = Any()
    private var targetLost = false
    private var execAdmitted = false
    private var command: Session.Command? = null
    private var client: SSHClient? = null
    private var worker: Job? = null
    private var closer: Job? = null

    /**
     * Target loss stops resources, but is a tool failure, not revocation of the caller's Send. Non-blocking, any thread:
     * it only sets a flag and cancels two jobs; the socket is closed by the closer on an IO worker.
     */
    fun loseTarget() {
        val jobs = synchronized(lock) { targetLost = true; listOfNotNull(worker, closer) }
        jobs.forEach { it.cancel() }
    }
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (synchronized(lock) { targetLost }) throw CancellationException()
    }
    private fun disconnectClient() {
        synchronized(lock) { client }?.let { runCatching { it.disconnect() } }
    }
    /** SSHJ caches these nullable facts independently of stream consumption, including after channel close. */
    private fun receivedExit(): SshExit? {
        val running = synchronized(lock) { command } ?: return null
        val status = running.exitStatus
        val signal = running.exitSignal
        return if (status == null && signal == null) null else SshExit(status, signal?.name)
    }
    private fun targetFailure(): OpResult<SshExecOutput> {
        val received = receivedExit()
        return when {
            !execAdmitted -> OpResult.Refused(SshRefusal.TargetLostBeforeExec)
            received != null -> OpResult.Failed(SshFailure.TargetLost(received), RemoteOutcome.KNOWN)
            else -> OpResult.Failed(SshFailure.TargetLost(null), RemoteOutcome.UNKNOWN)
        }
    }

    suspend fun run(work: suspend () -> OpResult<SshExecOutput>): OpResult<SshExecOutput> {
        admitSideEffect("ssh-dispatch")
        var result: OpResult<SshExecOutput>? = null
        try {
            coroutineScope {
                val finished = AtomicBoolean(false)
                // Covers the whole operation, including phases between blocking calls and cleanup of a cancelled worker.
                val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally {
                        if (!finished.get()) withContext(NonCancellable + Dispatchers.IO) {
                            // May still be on the caller thread (UNDISPATCHED, or cancelled before suspending): hop to IO.
                            yield()
                            disconnectClient()
                        }
                    }
                }
                val worker = async(Dispatchers.IO) { try { checkActive(); work() } finally { finished.set(true) } }
                val lost = synchronized(lock) { this@SshCommandOperation.worker = worker; this@SshCommandOperation.closer = closer; targetLost }
                if (lost) { worker.cancel(); closer.cancel() }
                try { result = worker.await() } finally { closer.cancel() }
            }
        } catch (failure: Exception) {
            // Target loss can also make construction/reads fail; only an active caller gets the local failure below.
            currentCoroutineContext().ensureActive()
            if (!synchronized(lock) { targetLost }) throw failure
        }
        // Stop/timeout/owner revocation wins even when it races a local target failure.
        currentCoroutineContext().ensureActive()
        return synchronized(lock) { if (targetLost) targetFailure() else checkNotNull(result) }
    }

    suspend fun execute(connector: SshConnector, command: String, available: () -> Boolean): OpResult<SshExecOutput> {
        val ssh = connector.newClient()
        synchronized(lock) { client = ssh }
        var session: Session? = null
        try {
            checkActive()
            if (!available()) return OpResult.Refused(SshRefusal.TargetUnavailable)
            connector.connect(ssh); checkActive()
            connector.authenticate(ssh); checkActive()
            if (!available()) return OpResult.Refused(SshRefusal.TargetUnavailable)
            session = ssh.startSession(); checkActive()
            if (!available()) return OpResult.Refused(SshRefusal.TargetUnavailable)
            // After this point a failure may have uncertain remote effects.
            synchronized(lock) { execAdmitted = true }
            val running = session.exec(command).also { synchronized(lock) { this.command = it } }
            running.outputStream.close()
            return coroutineScope {
                val stdout = async(Dispatchers.IO) { captureSshStream(running.inputStream) }
                val stderr = async(Dispatchers.IO) { captureSshStream(running.errorStream) }
                try {
                    val (out, err) = fitSshStreams(stdout.await(), stderr.await())
                    running.join()
                    checkActive()
                    val status = running.exitStatus
                    val signal = running.exitSignal
                    OpResult.Done(SshExecOutput(out, err, status, signal?.name))
                } catch (failure: Exception) {
                    // Unblock the sibling stream before structured scope waits for it.
                    runCatching { ssh.disconnect() }
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: HostKeyRejected) { return OpResult.Refused(SshRefusal.NeedsSetup) }
        catch (_: net.schmizz.sshj.userauth.UserAuthException) { return OpResult.Refused(SshRefusal.NeedsSetup) }
        catch (_: Exception) {
            val received = receivedExit()
            return when {
                received != null -> OpResult.Failed(SshFailure.CommandFailed(received), RemoteOutcome.KNOWN)
                synchronized(lock) { execAdmitted } -> OpResult.Failed(SshFailure.CommandFailed(null), RemoteOutcome.UNKNOWN)
                else -> OpResult.Refused(SshRefusal.ConnectFailed)
            }
        } finally {
            runCatching { session?.close() }
            runCatching { ssh.disconnect() }
        }
    }
}

/** Reserve for the labels, the two truncation notes and the exit facts inside the kernel's tool-result cap. */
internal const val SSH_RESULT_OVERHEAD = 200
/**
 * Stream text (stdout and stderr together) per result. Derived from the kernel's tool-result cap so the kernel never cuts
 * an ssh_command result in the middle; the output goes to the provider, so never raise it above that cap.
 */
internal val SSH_RESULT_BUDGET = AgentLimits().maxToolChars - SSH_RESULT_OVERHEAD
/** Stderr, when present, keeps at least this much (or its whole length if shorter); stdout gets the rest. */
internal const val SSH_STDERR_MIN = 1_500

/** The retained head of one stream and its exact total length, both in UTF-16 characters. */
internal class SshCapture(val head: String, val total: Long)

/** Retain at most [SSH_RESULT_BUDGET] characters; keep draining and counting the rest without retaining it (no channel deadlock). */
internal fun captureSshStream(stream: InputStream): SshCapture {
    val text = StringBuilder()
    var total = 0L
    val reader = stream.reader(Charsets.UTF_8)
    val buffer = CharArray(2048)
    while (true) {
        val n = reader.read(buffer)
        if (n < 0) break
        total += n
        val keep = minOf(n, SSH_RESULT_BUDGET - text.length)
        if (keep > 0) text.append(buffer, 0, keep)
    }
    return SshCapture(text.toString(), total)
}

/** Cuts [text] to [keep] characters without splitting a surrogate pair; a cut stream ends with "truncated: N of M characters". */
private fun clipSshStream(capture: SshCapture, keep: Int): String {
    var n = minOf(keep, capture.head.length)
    if (n >= capture.total) return capture.head.take(n)
    if (n > 0 && capture.head[n - 1].isHighSurrogate()) n--
    return capture.head.take(n) + "\n[truncated: $n of ${capture.total} characters]"
}

/** Shares [SSH_RESULT_BUDGET] between the streams: stderr keeps at least [SSH_STDERR_MIN] and stdout gets the rest. */
internal fun fitSshStreams(out: SshCapture, err: SshCapture): Pair<String, String> {
    val errKeep = minOf(err.head.length, maxOf(SSH_STDERR_MIN, SSH_RESULT_BUDGET - out.head.length))
    val outKeep = minOf(out.head.length, SSH_RESULT_BUDGET - errKeep)
    return clipSshStream(out, outKeep) to clipSshStream(err, errKeep)
}
