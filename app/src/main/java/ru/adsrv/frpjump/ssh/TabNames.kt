package ru.adsrv.frpjump.ssh

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/** The most code points a tab name has. */
const val TAB_NAME_MAX = 24

/** Why a typed tab name cannot be saved. */
enum class TabNameProblem {
    /** More than [TAB_NAME_MAX] code points after trimming. */
    TOO_LONG,

    /** A control, format/invisible or line-separating character, an unpaired surrogate, or `|`. */
    BAD_CHARACTER,
}

/** What [checkTabName] says about a typed name. */
sealed interface TabNameCheck {
    /** Can be saved: [name] is the trimmed text, or null for none (back to the automatic label). */
    data class Valid(val name: String?) : TabNameCheck

    /** Cannot be saved; [length] is the code points of the trimmed text. */
    data class Invalid(val problem: TabNameProblem, val length: Int) : TabNameCheck
}

/**
 * The one rule for an owner-set tab name: trimmed; at most [TAB_NAME_MAX]
 * code points; one line, with no control, format (bidi controls, zero-width
 * and other invisible ones) or separator-of-lines characters, no unpaired
 * surrogate, and no `|`. Empty after trimming is [TabNameCheck.Valid] with
 * a null name: the automatic label again.
 */
fun checkTabName(input: String): TabNameCheck {
    val text = input.trim { it.isWhitespace() || Character.isSpaceChar(it) }
    val points = text.codePoints().toArray()
    if (points.any { !tabNameCharacter(it) }) return TabNameCheck.Invalid(TabNameProblem.BAD_CHARACTER, points.size)
    if (points.size > TAB_NAME_MAX) return TabNameCheck.Invalid(TabNameProblem.TOO_LONG, points.size)
    return TabNameCheck.Valid(text.ifEmpty { null })
}

private fun tabNameCharacter(cp: Int): Boolean {
    if (cp == '|'.code) return false
    return when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> false
        else -> true
    }
}

/** A remembered name's tab: where it logs in and the tab's tmux session (`frpjump-<install>-<n>`). */
data class TabNameKey(val profile: String, val device: String, val port: Int, val user: String, val session: String)

/**
 * Owner-set tab names remembered app-privately, for tabs that run tmux, so
 * that a tab attached again to its session (after the app was killed, or
 * its tunnel came back) has its name back. Keyed by [TabNameKey]. At most
 * [max] are kept: the oldest written go first. A name goes when the app
 * ends its tmux session, when the owner clears it, and when a successful
 * session listing for the same profile, device, port and user does not
 * have the session ([afterListing]); a failed listing forgets nothing.
 * Thread-safe. The names are owner text, not secrets.
 */
class TabNameStore(private val storage: TextStorage, private val max: Int = MAX_REMEMBERED) {
    @Serializable
    private data class Entry(val profile: String, val device: String, val port: Int, val user: String, val session: String, val name: String)

    @Serializable
    private data class Stored(val names: List<Entry> = emptyList())

    /** Oldest written first. */
    private var entries: MutableList<Entry>? = null

    /** The remembered name of [key], or null. */
    @Synchronized
    fun get(key: TabNameKey): String? = load().firstOrNull { it.matches(key) }?.name

    /** Remembers [name] (valid, see [checkTabName]) for [key]; the newest write. Nothing is written when it is the same. */
    @Synchronized
    fun put(key: TabNameKey, name: String) {
        val list = load()
        val at = list.indexOfFirst { it.matches(key) }
        if (at == list.lastIndex && at >= 0 && list[at].name == name) return
        if (at >= 0) list.removeAt(at)
        list += Entry(key.profile, key.device, key.port, key.user, key.session, name)
        while (list.size > max) list.removeAt(0)
        save()
    }

    /** Forgets [key]'s name. */
    @Synchronized
    fun forget(key: TabNameKey) {
        val list = load()
        if (list.removeAll { it.matches(key) }) save()
    }

    /**
     * A listing of this install's sessions for [user] on [device]:[port] of
     * [profile]: [listed], or null when it failed (nothing is forgotten).
     * Names of sessions it does not have are forgotten, except those in
     * [keep] (sessions of tabs open now: they may have started after the
     * listing was taken).
     */
    @Synchronized
    fun afterListing(profile: String, device: String, port: Int, user: String, listed: Collection<String>?, keep: Set<String> = emptySet()) {
        if (listed == null) return
        val list = load()
        val gone = list.filter { it.profile == profile && it.device == device && it.port == port && it.user == user && it.session !in listed && it.session !in keep }
        if (gone.isNotEmpty()) {
            list.removeAll(gone.toSet())
            save()
        }
    }

    private fun Entry.matches(k: TabNameKey) =
        profile == k.profile && device == k.device && port == k.port && user == k.user && session == k.session

    private fun load(): MutableList<Entry> {
        entries?.let { return it }
        val read = runCatching { storage.read()?.let { WireJson.decodeFromString<Stored>(it).names } }.getOrNull().orEmpty()
            // A foreign or edited file: only well-formed names, once each, and not more than the cap.
            .filter { isTmuxName(it.session) && checkTabName(it.name).let { c -> c is TabNameCheck.Valid && c.name == it.name } }
            .reversed().distinctBy { listOf(it.profile, it.device, it.port, it.user, it.session) }.reversed()
            .takeLast(max)
        return read.toMutableList().also { entries = it }
    }

    private fun save() {
        try {
            storage.write(WireJson.encodeToString(Stored(entries.orEmpty())))
        } catch (e: Exception) {
            // Kept in memory for this run.
            Log.w(TAG, "tab names not saved: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "TabNames"

        /** The most names remembered. */
        const val MAX_REMEMBERED = 200
    }
}

/** A [TextStorage] that keeps nothing past the process; for tests and as the default of an environment that has no file. */
class MemoryTextStorage : TextStorage {
    @Volatile
    private var text: String? = null

    override fun read() = text

    override fun write(text: String) {
        this.text = text
    }

    override fun delete() {
        text = null
    }
}
