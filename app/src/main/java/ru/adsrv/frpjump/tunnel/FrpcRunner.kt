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
    val configFor: (TlsFiles, FrpcAdmin) -> String,
    val certPem: String,
    val keyPem: String,
    val caPem: String,
    val visitors: List<VisitorSpec>,
    val relayOnly: Boolean,
    /** Server, device and token: frpc applies them only at start. */
    val connection: String,
) {
    val grantIds: List<String> get() = visitors.map { it.grantId }

    /** Whether going from [old] to this needs a new frpc rather than a reload of its visitors. */
    fun needsRestartFrom(old: FrpcLaunch): Boolean =
        connection != old.connection || certPem != old.certPem || keyPem != old.keyPem || caPem != old.caPem ||
            relayOnly != old.relayOnly

    /** Grants whose visitors a reload from [old] starts (new or changed), in config order. */
    fun restartedSince(old: FrpcLaunch): List<String> {
        val before = old.visitors.associateBy { it.grantId }
        return visitors.filter { before[it.grantId] != it }.map { it.grantId }
    }

    override fun toString() = "FrpcLaunch(grants=$grantIds, relayOnly=$relayOnly)"
}

/**
 * Runs one frpc child process, as the Linux agent's `FrpDriver` +
 * `SubprocessSupervisor` do: write frpc.toml, start frpc when it is not
 * running, SIGTERM then kill after 5 s. Unlike the agent, a change of
 * tunnels does not restart frpc: the new config is written and frpc is
 * told to reload it through its admin API ([FrpcAdmin]), so tunnels that
 * stay keep their open connections (SSH sessions, web sockets). frpc is
 * restarted only when the reload fails or something it reads only at
 * start changed ([FrpcLaunch.needsRestartFrom]).
 *
 * Files live in [workDir] (noBackupFilesDir), each created 0600. The
 * private key (and the config, which holds the API token and grant
 * secrets) exist on disk only while frpc runs: [stop] deletes them.
 * The cert and CA are public and stay. Both are also removed when frpc
 * exits by itself and when the runner is created: a process that was
 * killed (with frpc as its child) never got to [stop].
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

    /** The running frpc and what it was last given. */
    private class Run(
        val process: Process,
        val admin: FrpcAdmin,
        val parser: FrpcLogParser,
        var launch: FrpcLaunch,
        var config: String,
    )

    private var run: Run? = null

    /** Bumped on every start and stop; a log pump only publishes for its own run. */
    @Volatile
    private var generation = 0

    /** Local ports the running frpc listens on (or is about to). */
    @Volatile
    override var heldPorts: Set<Int> = emptySet()
        private set

    /** How the last [apply] got there, for tests and diagnostics. */
    @Volatile
    var lastChange: Change = Change.NONE
        private set

    enum class Change { NONE, STARTED, RELOADED }

    init {
        deleteSecrets()
    }

    val isRunning: Boolean
        @Synchronized get() = run?.process?.isAlive == true

    /** Starts frpc, or has the running one reload [launch]; nothing when it already runs it. */
    @Synchronized
    override fun apply(launch: FrpcLaunch, ports: Set<Int>) {
        val current = run
        if (current != null && current.process.isAlive) {
            val config = launch.configFor(tls, current.admin)
            if (config == current.config) {
                lastChange = Change.NONE
                return
            }
            if (!launch.needsRestartFrom(current.launch) && reload(current, launch, config)) {
                heldPorts = ports
                lastChange = Change.RELOADED
                return
            }
        }
        start(launch, ports)
        lastChange = Change.STARTED
    }

    private fun reload(current: Run, launch: FrpcLaunch, config: String): Boolean {
        current.parser.reconfigure(launch.grantIds, launch.restartedSince(current.launch))
        _states.value = current.parser.snapshot()
        writePrivateFile(configFile, config)
        current.launch = launch
        current.config = config
        if (current.admin.reload()) {
            log.add("frpc reloaded ${launch.visitors.size} tunnel(s)")
            return true
        }
        log.add("frpc reload failed; restarting it")
        return false
    }

    private fun start(launch: FrpcLaunch, ports: Set<Int>) {
        stopProcess()
        // New credentials for every run: the old ones die with the old process.
        val admin = FrpcAdmin.random()
        val config = launch.configFor(tls, admin)
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
        run = Run(started, admin, parser, launch, config)
        heldPorts = ports
        val myGeneration = ++generation
        thread(name = "frpc-log", isDaemon = true) { pump(started, parser, myGeneration) }
    }

    /** Stops frpc and removes the key and config from disk. */
    @Synchronized
    override fun stop() {
        stopProcess()
        deleteSecrets()
        _states.value = emptyMap()
    }

    private fun deleteSecrets() {
        File(tls.keyFile).delete()
        configFile.delete()
    }

    @Synchronized
    override fun wipe() {
        stop()
        workDir.deleteRecursively()
    }

    override fun recentLog(): List<String> = log.snapshot()

    private fun stopProcess() {
        generation++
        val p = run?.process ?: return
        run = null
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
        synchronized(this) {
            // Exited by itself (not via stopProcess, which bumps the generation first):
            // nothing reads the key and the config any more. The next apply starts it again.
            if (myGeneration == generation) {
                run = null
                heldPorts = emptySet()
                deleteSecrets()
            }
        }
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
