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

/** A session the app remembers: where it logs in and its tmux session (`frpjump-<install>-<n>`). */
data class SessionKey(val profile: String, val device: String, val port: Int, val user: String, val session: String)

/** What is remembered of a session besides where it is: the name it was given, if any, and who opened it. */
data class SessionRecord(val name: TabName?, val openedBy: SessionOpener)

/**
 * The sessions the app remembers app-privately, for sessions that run tmux, so that after the app was killed or a tunnel came back a
 * session has its name back and the assistant's own sessions can still be listed: ONE store of [SessionRecord]s keyed by [SessionKey]
 * (the tab names of the first version are records of the owner's sessions; a file written then reads as it is). At most [max] are kept:
 * the oldest written go first. A record goes when the app ends its tmux session, when the owner clears the name of a session of his own,
 * and when a successful session listing for the same profile, device, port and user does not have the session ([afterListing]); a failed
 * listing forgets nothing. The record of an assistant session stays when its name is cleared (it is how the session is found again).
 * Thread-safe. Names are owner or model text, not secrets; kept out of backups with the rest of the app's private files.
 */
class SessionRecordStore(private val storage: TextStorage, private val max: Int = MAX_REMEMBERED) {
    @Serializable
    private data class Entry(
        val profile: String, val device: String, val port: Int, val user: String, val session: String,
        /** Empty: no name (only an assistant session's record has none). */
        val name: String = "",
        /** `owner` or `assistant`; an entry without it (written before the field existed) is the owner's. */
        val by: String = "owner",
        /** `owner` or `assistant`: who opened the session; an entry without it is the owner's. */
        val opened: String = "owner",
    ) {
        fun setter() = if (by == "assistant") NameSetter.ASSISTANT else NameSetter.OWNER
        fun opener() = SessionOpener.fromWire(opened)
        fun record() = SessionRecord(name.takeIf { it.isNotEmpty() }?.let { TabName(it, setter()) }, opener())
    }

    @Serializable
    private data class Stored(val names: List<Entry> = emptyList())

    /** Oldest written first. */
    private var entries: MutableList<Entry>? = null

    private val _changed = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Counts every change of the records, so that whoever derives from them (the assistant's session list) follows them. */
    val changed: kotlinx.coroutines.flow.StateFlow<Int> = _changed

    /** The record of [key], or null. May read the file: not for the main thread. */
    @Synchronized
    fun record(key: SessionKey): SessionRecord? = load().firstOrNull { it.matches(key) }?.record()

    /** The remembered name of [key] and who set it, or null. May read the file: not for the main thread. */
    fun get(key: SessionKey): TabName? = record(key)?.name

    /** Every session the assistant opened that is remembered, oldest first. May read the file: not for the main thread. */
    @Synchronized
    fun assistantSessions(): List<Pair<SessionKey, SessionRecord>> =
        load().filter { it.opener() == SessionOpener.ASSISTANT }.map { SessionKey(it.profile, it.device, it.port, it.user, it.session) to it.record() }

    /**
     * Remembers [name] (valid, see [checkTabName]; null for none) set [by] for [key], opened by [openedBy] when the session is new here (a
     * known session keeps who opened it); the newest write. Nothing is written when it is the same.
     */
    @Synchronized
    fun put(key: SessionKey, name: String?, by: NameSetter = NameSetter.OWNER, openedBy: SessionOpener? = null) {
        val list = load()
        val at = list.indexOfFirst { it.matches(key) }
        val opened = (openedBy ?: if (at >= 0) list[at].opener() else SessionOpener.OWNER).wire
        val tag = if (by == NameSetter.ASSISTANT) "assistant" else "owner"
        val text = name.orEmpty()
        // A session of the owner's without a name is not a record.
        if (text.isEmpty() && opened == SessionOpener.OWNER.wire) { forget(key); return }
        if (at == list.lastIndex && at >= 0 && list[at].name == text && list[at].by == tag && list[at].opened == opened) return
        if (at >= 0) list.removeAt(at)
        list += Entry(key.profile, key.device, key.port, key.user, key.session, text, if (text.isEmpty()) "owner" else tag, opened)
        trim(list)
        save()
    }

    /** The owner or the assistant cleared the name of [key]: an owner's session has no record then, an assistant's keeps one without a name. */
    @Synchronized
    fun clearName(key: SessionKey) {
        val at = load().indexOfFirst { it.matches(key) }
        if (at < 0) return
        if (entries!![at].opener() == SessionOpener.ASSISTANT) put(key, null) else forget(key)
    }

    /** Forgets [key]. */
    @Synchronized
    fun forget(key: SessionKey) {
        val list = load()
        if (list.removeAll { it.matches(key) }) save()
    }

    /** Durable removal for a confirmed owner close: publish only after the file was written. */
    @Synchronized
    fun forgetConfirmed(key: SessionKey) {
        val before = load()
        val next = before.filterNot { it.matches(key) }
        storage.write(WireJson.encodeToString(Stored(next)))
        if (next.size != before.size) {
            entries = next.toMutableList()
            _changed.value += 1
        }
    }

    /**
     * A listing of this install's sessions (every one, whoever opened it) for [user] on [device]:[port] of
     * [profile]: [listed], or null when it failed (nothing is forgotten).
     * Records of sessions it does not have are forgotten, except those in
     * [keep] (sessions open now: they may have started after the
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

    /**
     * Sessions marked as the assistant's on [device]:[port] for [user] in a successful listing ([names]) that have no record (it was lost: the
     * cap, "Forget profile", a file that did not save) get their record back.
     */
    @Synchronized
    fun adoptAssistant(profile: String, device: String, port: Int, user: String, names: Collection<String>) {
        for (name in names) {
            val key = SessionKey(profile, device, port, user, name)
            if (load().none { it.matches(key) }) put(key, null, openedBy = SessionOpener.ASSISTANT)
        }
    }

    /** The profile was removed ("Forget profile"): its records go. */
    @Synchronized
    fun forgetProfile(profile: String) {
        val list = load()
        if (list.removeAll { it.profile == profile }) save()
    }

    private fun Entry.matches(k: SessionKey) =
        profile == k.profile && device == k.device && port == k.port && user == k.user && session == k.session

    private fun load(): MutableList<Entry> {
        entries?.let { return it }
        val read = runCatching { storage.read()?.let { WireJson.decodeFromString<Stored>(it).names } }.getOrNull().orEmpty()
            // A foreign or edited file: only well-formed records, once each, and not more than the cap.
            .filter { isTmuxName(it.session) && (it.name.isEmpty() && it.opener() == SessionOpener.ASSISTANT || checkTabName(it.name).let { c -> c is TabNameCheck.Valid && c.name == it.name && it.name.isNotEmpty() }) }
            .reversed().distinctBy { listOf(it.profile, it.device, it.port, it.user, it.session) }.reversed()
        return read.toMutableList().also { trim(it); entries = it }
    }

    /** Past the cap the oldest of the OWNER's records go; the assistant's never (their number is bounded by the owner's limit of sessions, and a record is how one is found). */
    private fun trim(list: MutableList<Entry>) {
        while (list.size > max) {
            val at = list.indexOfFirst { it.opener() == SessionOpener.OWNER }
            if (at < 0) return
            list.removeAt(at)
        }
    }

    private fun save() {
        _changed.value += 1
        try {
            storage.write(WireJson.encodeToString(Stored(entries.orEmpty())))
        } catch (e: Exception) {
            // Kept in memory for this run.
            Log.w(TAG, "session records not saved: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "SessionRecords"

        /** The most records remembered. */
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
