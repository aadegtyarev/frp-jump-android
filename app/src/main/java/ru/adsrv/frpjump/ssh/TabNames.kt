package ru.adsrv.frpjump.ssh

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.assistant.chat.receiptText
import ru.adsrv.frpjump.store.TextStorage

/** The most code points a tab name has. */
const val TAB_NAME_MAX = 24

/** Why a typed tab name cannot be saved. */
enum class TabNameProblem {
    /** More than [TAB_NAME_MAX] code points after trimming. */
    TOO_LONG,

    /**
     * A control, format/invisible or line-separating character, an unpaired surrogate, a character that the chat would not show as it is
     * (invisible fillers, private-use or unassigned, a run of spaces), nothing visible at all, or one of `"` `«` `»` `·` `|`.
     */
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
 * The one rule for a tab name, the owner's and the assistant's: trimmed; at most [TAB_NAME_MAX]
 * code points; one line, with no control, format (bidi controls, zero-width
 * and other invisible ones) or separator-of-lines characters, no unpaired
 * surrogate, none of `"` `«` `»` `·` `|` (the marks the app puts around and
 * between the parts of a tab's description, so a name cannot imitate them);
 * and shown as it is by [receiptText] (which drops invisible fillers such as
 * U+3164, U+115F, U+FFA0, U+2800, U+034F and variation selectors, private-use and
 * unassigned code points, and collapses runs of spaces), with at least one visible
 * character. Empty after trimming is [TabNameCheck.Valid] with a null name: the
 * automatic label again.
 */
fun checkTabName(input: String): TabNameCheck {
    val text = input.trim { it.isWhitespace() || Character.isSpaceChar(it) }
    val points = text.codePoints().toArray()
    if (points.any { !tabNameCharacter(it) }) return TabNameCheck.Invalid(TabNameProblem.BAD_CHARACTER, points.size)
    if (text.isNotEmpty() && (receiptText(text, Int.MAX_VALUE) != text || points.none(::visible))) {
        return TabNameCheck.Invalid(TabNameProblem.BAD_CHARACTER, points.size)
    }
    if (points.size > TAB_NAME_MAX) return TabNameCheck.Invalid(TabNameProblem.TOO_LONG, points.size)
    return TabNameCheck.Valid(text.ifEmpty { null })
}

private fun tabNameCharacter(cp: Int): Boolean {
    if (cp == '|'.code || cp == '"'.code || cp == 0xAB || cp == 0xBB || cp == 0xB7) return false
    return when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> false
        else -> true
    }
}

/** A letter, number, punctuation or symbol: what a person can see (a mark alone is not a character). */
private fun visible(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
    else -> false
}

/** [text] as an isolated run (FSI ... PDI): whatever it holds cannot reorder the text around it. Wherever a name is shown. */
fun isolateName(text: String): String = "\u2068$text\u2069"

/** Who gave a tab its name. A name the assistant gave is shown with the automatic label until the owner saves it. */
enum class NameSetter { OWNER, ASSISTANT }

/** A tab's name and who set it. */
data class TabName(val text: String, val setBy: NameSetter = NameSetter.OWNER)

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
    private data class Entry(
        val profile: String, val device: String, val port: Int, val user: String, val session: String, val name: String,
        /** `owner` or `assistant`; an entry without it (written before the field existed) is the owner's. */
        val by: String = "owner",
    ) {
        fun setter() = if (by == "assistant") NameSetter.ASSISTANT else NameSetter.OWNER
    }

    @Serializable
    private data class Stored(val names: List<Entry> = emptyList())

    /** Oldest written first. */
    private var entries: MutableList<Entry>? = null

    /** The remembered name of [key] and who set it, or null. May read the file: not for the main thread. */
    @Synchronized
    fun get(key: TabNameKey): TabName? = load().firstOrNull { it.matches(key) }?.let { TabName(it.name, it.setter()) }

    /** Remembers [name] (valid, see [checkTabName]) set [by] for [key]; the newest write. Nothing is written when it is the same. */
    @Synchronized
    fun put(key: TabNameKey, name: String, by: NameSetter = NameSetter.OWNER) {
        val list = load()
        val at = list.indexOfFirst { it.matches(key) }
        val tag = if (by == NameSetter.ASSISTANT) "assistant" else "owner"
        if (at == list.lastIndex && at >= 0 && list[at].name == name && list[at].by == tag) return
        if (at >= 0) list.removeAt(at)
        list += Entry(key.profile, key.device, key.port, key.user, key.session, name, tag)
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
