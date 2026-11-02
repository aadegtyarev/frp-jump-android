package ru.adsrv.frpjump.ssh.diagnostics

import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException

/** Uses the existing authenticated transport; no terminal bytes, shell exec or new connection. */
class SshjResponseProbe(private val client: SSHClient) : SshProbeSender {
    override fun send(): PendingSshProbe {
        val promise = client.connection.sendGlobalRequest(REQUEST, true, byteArrayOf())
        return object : PendingSshProbe {
            override val complete: Boolean get() = promise.isFulfilled

            override fun await(timeoutMs: Long): ProbeReply = try {
                if (promise.tryRetrieve(timeoutMs, TimeUnit.MILLISECONDS) != null) ProbeReply.REPLIED else ProbeReply.TIMEOUT
            } catch (e: ConnectionException) {
                // SSHJ 0.41.2 ConnectionImpl.gotGlobalReqResponse delivers this exact
                // exception for REQUEST_FAILURE. Any other exception stays a failure.
                // Negative keepalive replies are normal on servers without this extension.
                if (isNegativeReply(e, promise.toString())) ProbeReply.REPLIED else ProbeReply.FAILED
            } catch (_: Exception) {
                ProbeReply.FAILED
            }
        }
    }

    companion object {
        const val REQUEST = "keepalive@openssh.com"

        internal fun isNegativeReply(error: ConnectionException, promiseName: String): Boolean =
            error.cause == null && error.message == "Global request [$promiseName] failed"
    }
}
