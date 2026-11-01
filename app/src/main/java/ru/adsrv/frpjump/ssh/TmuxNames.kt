package ru.adsrv.frpjump.ssh

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage
import java.security.SecureRandom
import java.util.Random

/**
 * Names of the app's tmux sessions, `frpjump-<install>-<n>`: `<install>`
 * is 6 random hex characters made once per app install, `<n>` a counter
 * that only grows. Both are kept in [storage] (app-private), so a name is
 * never given twice, even after the app was killed while its sessions
 * still run on a device. Thread-safe.
 */
class TmuxNames(private val storage: TextStorage, private val random: Random = SecureRandom()) {
    @Serializable
    private data class Stored(val install: String, val next: Int)

    private var state: Stored? = null

    /** This install's code. */
    @get:Synchronized
    val install: String get() = load().install

    /** A name no tab of this install had before. */
    @Synchronized
    fun next(): String {
        val now = load()
        save(now.copy(next = now.next + 1))
        return "frpjump-${now.install}-${now.next}"
    }

    /**
     * Names picked up from a device ([names], this install's): the counter
     * moves past them, so a new tab never takes one of them, even if the
     * counter was not saved when they were made.
     */
    @Synchronized
    fun reserve(names: List<String>) {
        val now = load()
        val highest = names.filter { it.startsWith("frpjump-${now.install}-") }.maxOfOrNull { tmuxNumber(it) } ?: return
        if (highest >= now.next) save(now.copy(next = (highest + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
    }

    private fun load(): Stored {
        state?.let { return it }
        val read = runCatching { storage.read()?.let { WireJson.decodeFromString<Stored>(it) } }.getOrNull()
            ?.takeIf { INSTALL.matches(it.install) && it.next >= 1 }
        val loaded = read ?: Stored(newInstall(), 1).also(::save)
        state = loaded
        return loaded
    }

    private fun save(s: Stored) {
        state = s
        try {
            storage.write(WireJson.encodeToString(s))
        } catch (e: Exception) {
            // Kept in memory for this run; the names still differ within it.
            Log.w(TAG, "tmux names not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun newInstall(): String = (0 until 3).joinToString("") { "%02x".format(random.nextInt(256)) }

    private companion object {
        const val TAG = "TmuxNames"
        val INSTALL = Regex("[0-9a-f]{6}")
    }
}

/**
 * The tmux side of one terminal tab: its session name, taken from [names]
 * on first use and then the same for every connection of the tab, so a
 * reconnect re-attaches where it was; and whether the connection now open
 * runs tmux. In memory only: the tab keeps it while it lives, and a tab
 * that replaces it in place (Reconnect) is handed [initial].
 */
class TabTmux(private val names: TmuxNames, initial: String? = null, disabled: Boolean = false) {
    /** The name once made (or handed over), else null. */
    @Volatile
    var assigned: String? = initial
        private set

    /** The tab's name, made on first call. */
    @Synchronized
    fun name(): String = assigned ?: names.next().also { assigned = it }

    /** Takes [name], a session picked up from the device, as the tab's; only before it has one. */
    @Synchronized
    fun adopt(name: String): Boolean {
        if (assigned != null) return false
        assigned = name
        return true
    }

    /** tmux failed to start in this tab ([tmuxFailedToStart]): it opens plain shells from now on. */
    @Volatile
    var disabled: Boolean = disabled

    /** The connection now open runs tmux ([TmuxProbe.FRESH] or [TmuxProbe.EXISTING]). */
    @Volatile
    var inUse = false
}
