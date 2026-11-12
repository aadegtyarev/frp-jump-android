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
                    val out = stdout.await(); val err = stderr.await()
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
