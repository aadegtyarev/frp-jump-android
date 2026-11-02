package ru.adsrv.frpjump.ssh.diagnostics

/** A negative protocol reply is still a reply; transport errors/timeouts are not. */
enum class ProbeReply { REPLIED, TIMEOUT, FAILED }

interface PendingSshProbe {
    val complete: Boolean
    fun await(timeoutMs: Long): ProbeReply
}

fun interface SshProbeSender { fun send(): PendingSshProbe }

/** Called on one IO worker, never the UI/writer thread. Does not cancel or retry a pending request. */
class SshResponseProbe(
    private val metrics: TerminalMetrics,
    private val now: () -> Long = System::nanoTime,
) {
    private data class Pending(val token: ObservationToken, val request: PendingSshProbe)
    private var pending: Pending? = null
    private var lastAttemptNs: Long? = null

    fun sampleBlocking(sender: SshProbeSender, stillCurrent: () -> Boolean) {
        val token = metrics.token() ?: return
        if (!stillCurrent()) return
        pending?.let { old ->
            if (old.token.connection != token.connection) {
                // The old client owns its own pending request; never touch its protocol queue.
                pending = null
                lastAttemptNs = null
            } else if (!old.request.complete) {
                return
            } else {
                // A late reply only frees the slot. It is never reported as a fresh measurement.
                pending = null
            }
        }
        val start = now()
        if (lastAttemptNs?.let { start - it < INTERVAL_NS } == true) return
        lastAttemptNs = start
        val request = try { sender.send() } catch (_: Exception) {
            if (stillCurrent()) metrics.probeFailed(token, timeout = false)
            return
        }
        pending = Pending(token, request)
        val answer = try { request.await(TIMEOUT_MS) } catch (_: Exception) { ProbeReply.FAILED }
        if (request.complete) pending = null
        if (!stillCurrent()) return
        when (answer) {
            ProbeReply.REPLIED -> metrics.probeReplied(token, now() - start)
            ProbeReply.TIMEOUT -> metrics.probeFailed(token, timeout = true)
            ProbeReply.FAILED -> metrics.probeFailed(token, timeout = false)
        }
    }

    companion object {
        const val TIMEOUT_MS = 3000L
        const val INTERVAL_NS = 20_000_000_000L
    }
}
