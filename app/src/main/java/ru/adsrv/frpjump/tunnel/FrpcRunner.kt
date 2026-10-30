package ru.adsrv.frpjump.tunnel

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Everything frpc needs for one run; [toString] leaves the secrets out. */
class FrpcLaunch(
    val configFor: (TlsFiles) -> String,
    val certPem: String,
    val keyPem: String,
    val caPem: String,
    val grantIds: List<String>,
    val relayOnly: Boolean,
) {
    override fun toString() = "FrpcLaunch(grants=$grantIds, relayOnly=$relayOnly)"
}

/**
 * Runs one frpc child process, as the Linux agent's `FrpDriver` +
 * `SubprocessSupervisor` do: write frpc.toml, (re)start frpc only when the
 * config text changed or the process is gone, SIGTERM then kill after 5 s.
 *
 * Files live in [workDir] (noBackupFilesDir), each created 0600. The
 * private key (and the config, which holds the API token and grant
 * secrets) exist on disk only while frpc runs: [stop] deletes them.
 * The cert and CA are public and stay.
 */
class FrpcRunner(private val binary: File, private val workDir: File) : FrpcControl {
    private val configFile = File(workDir, "frpc.toml")
    private val tls = TlsFiles(
        certFile = File(workDir, "tls.crt").path,
        keyFile = File(workDir, "tls.key").path,
        caFile = File(workDir, "ca.crt").path,
    )

    val log = LogRing()

    private val _states = MutableStateFlow<Map<String, VisitorState>>(emptyMap())

    /** Per grant id, from [FrpcLogParser]; empty while frpc is not running. */
    override val states: StateFlow<Map<String, VisitorState>> = _states

    private var process: Process? = null
    private var appliedConfig: String? = null
    /** Bumped on every start and stop; a log pump only publishes for its own run. */
    @Volatile
    private var generation = 0

    /** Local ports the running frpc listens on (or is about to). */
    @Volatile
    override var heldPorts: Set<Int> = emptySet()
        private set

    val isRunning: Boolean
        @Synchronized get() = process?.isAlive == true

    /** Starts or restarts frpc unless it already runs this exact config. */
    @Synchronized
    override fun apply(launch: FrpcLaunch, ports: Set<Int>) {
        val config = launch.configFor(tls)
        if (config == appliedConfig && process?.isAlive == true) return
        stopProcess()
        writePrivateFile(File(tls.certFile), launch.certPem)
        writePrivateFile(File(tls.caFile), launch.caPem)
        writePrivateFile(File(tls.keyFile), launch.keyPem)
        writePrivateFile(configFile, config)
        val parser = FrpcLogParser(launch.grantIds, launch.relayOnly)
        _states.value = parser.snapshot()
        val started = ProcessBuilder(binary.path, "-c", configFile.path)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = started
        appliedConfig = config
        heldPorts = ports
        val myGeneration = ++generation
        thread(name = "frpc-log", isDaemon = true) { pump(started, parser, myGeneration) }
    }

    /** Stops frpc and removes the key and config from disk. */
    @Synchronized
    override fun stop() {
        stopProcess()
        File(tls.keyFile).delete()
        configFile.delete()
        _states.value = emptyMap()
    }

    @Synchronized
    override fun wipe() {
        stop()
        workDir.deleteRecursively()
    }

    override fun recentLog(): List<String> = log.snapshot()

    private fun stopProcess() {
        generation++
        val p = process ?: return
        process = null
        appliedConfig = null
        heldPorts = emptySet()
        p.destroy()
        if (!p.waitFor(TERM_GRACE_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            p.waitFor()
        }
    }

    private fun pump(p: Process, parser: FrpcLogParser, myGeneration: Int) {
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                log.add(line)
                if (BuildConfig.DEBUG) Log.d(TAG, line)
                publish(parser, myGeneration) { parser.feed(line) }
            }
        } catch (_: Exception) {
            // Stream closed by stop(): the exit below is ours, not frpc's.
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        log.add("frpc exited with code $code")
        publish(parser, myGeneration) { parser.exited(code); true }
    }

    /**
     * Applies a parser change only if this run is still the current one. Not
     * under the runner's lock: [stopProcess] holds it while frpc shuts down,
     * and frpc must be able to flush its last lines meanwhile.
     */
    private fun publish(parser: FrpcLogParser, myGeneration: Int, change: () -> Boolean) {
        if (change() && myGeneration == generation) _states.value = parser.snapshot()
    }

    private companion object {
        const val TAG = "frpc"
        const val TERM_GRACE_SECONDS = 5L
    }
}
