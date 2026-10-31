package ru.adsrv.frpjump.tunnel

/**
 * The frpc a killed app process may leave behind: Android normally kills
 * an app's whole process group, but if one survives it still holds the
 * local ports, and the frpc the app starts again could not listen on them.
 * The running frpc's pid is kept in a pidfile next to its config; a pid is
 * trusted only while its command line is still that frpc (the binary and
 * this profile's config), never another process that got the number since.
 */

/** The pid in [pidText] when it is still a frpc of [binary] run on [config]; [cmdline] reads `/proc/<pid>/cmdline`. */
fun staleFrpcPid(pidText: String?, binary: String, config: String, cmdline: (Int) -> String?): Int? {
    val pid = pidText?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return pid.takeIf { isFrpc(cmdline(pid), binary, config) }
}

/** Among [pids], the one running [binary] on [config]: the frpc just started, for the pidfile. */
fun frpcPidAmong(pids: List<Int>, binary: String, config: String, cmdline: (Int) -> String?): Int? =
    pids.firstOrNull { isFrpc(cmdline(it), binary, config) }

/** `/proc/<pid>/cmdline` is the arguments separated (and ended) by NUL. */
private fun isFrpc(cmdline: String?, binary: String, config: String): Boolean {
    val args = cmdline?.split('\u0000')?.filter { it.isNotEmpty() } ?: return false
    return args.firstOrNull() == binary && config in args
}
