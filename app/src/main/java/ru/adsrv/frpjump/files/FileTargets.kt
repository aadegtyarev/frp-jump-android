package ru.adsrv.frpjump.files

import ru.adsrv.frpjump.ssh.SshAuth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Whose files a file screen shows: a device and SSH port of a profile, and the login to use. */
data class FilesTarget(
    val profile: String,
    val device: String,
    val port: Int,
    val user: String,
    val auth: SshAuth,
)

/**
 * File screens opened in this run, by the id their route carries: a typed
 * password must not go into a route, so it waits here, in memory only.
 */
class FileTargets {
    private val next = AtomicInteger(1)
    private val targets = ConcurrentHashMap<Int, FilesTarget>()

    fun put(target: FilesTarget): Int = next.getAndIncrement().also { targets[it] = target }

    fun get(id: Int): FilesTarget? = targets[id]

    /** Another login for the same screen ("Change login"). */
    fun replace(id: Int, target: FilesTarget) {
        targets[id] = target
    }

    fun remove(id: Int) {
        targets.remove(id)
    }
}
