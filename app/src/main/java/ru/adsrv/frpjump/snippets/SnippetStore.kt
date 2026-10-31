package ru.adsrv.frpjump.snippets

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage
import java.util.UUID

/** A saved command of the terminal's snippets row. */
@Serializable
data class Snippet(
    @SerialName("id") val id: String,
    @SerialName("text") val text: String,
)

/** Longest command kept; a snippet is a line or two, not a script. */
const val MAX_SNIPPET_LENGTH = 1000

/** [text] as stored: trailing spaces and line breaks dropped (long press adds the Enter). Null when nothing is left. */
fun snippetText(text: String): String? = text.trimEnd().take(MAX_SNIPPET_LENGTH).takeIf { it.isNotBlank() }

/**
 * The places [id] moves in [all] to step [by] places among those shown
 * (the others are hidden: deleted, Undo still on screen); 0 when it cannot.
 */
fun stepsAmongShown(all: List<Snippet>, shown: (Snippet) -> Boolean, id: String, by: Int): Int {
    val visible = all.filter(shown)
    val at = visible.indexOfFirst { it.id == id }
    val target = visible.getOrNull(at + by)
    if (at < 0 || by == 0 || target == null) return 0
    return all.indexOf(target) - all.indexOfFirst { it.id == id }
}

/**
 * The terminal's snippets, one list per phone (all profiles and devices),
 * in the owner's order. Empty until [load]; every change writes the file.
 * Storage: call off the main thread.
 */
class SnippetStore(
    private val file: TextStorage,
    /** Where a file that does not parse is copied before anything is saved over it. */
    private val corruptCopy: TextStorage,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _snippets = MutableStateFlow<List<Snippet>>(emptyList())
    val snippets: StateFlow<List<Snippet>> = _snippets
    private var loaded = false

    /** Reads the file, once. */
    @Synchronized
    fun load() {
        if (loaded) return
        _snippets.value = read()
        loaded = true
    }

    /** Adds [text] at the end; null when it is blank. */
    @Synchronized
    fun add(text: String): Snippet? {
        load()
        val clean = snippetText(text) ?: return null
        return Snippet(newId(), clean).also { write(_snippets.value + it) }
    }

    /** False when there is no such snippet or [text] is blank. */
    @Synchronized
    fun update(id: String, text: String): Boolean {
        load()
        val clean = snippetText(text) ?: return false
        val now = _snippets.value
        if (now.none { it.id == id }) return false
        write(now.map { if (it.id == id) it.copy(text = clean) else it })
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        load()
        val now = _snippets.value
        if (now.none { it.id == id }) return false
        write(now.filter { it.id != id })
        return true
    }

    /** Moves a snippet [by] places (negative: towards the start); false when it cannot go there. */
    @Synchronized
    fun move(id: String, by: Int): Boolean {
        load()
        val now = _snippets.value.toMutableList()
        val from = now.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || by == 0 || to !in now.indices) return false
        now.add(to, now.removeAt(from))
        write(now)
        return true
    }

    private fun write(value: List<Snippet>) {
        file.write(WireJson.encodeToString(LIST, value))
        _snippets.value = value
    }

    /** The stored list; a file that does not parse is kept as [corruptCopy] (the first one) and reads as empty. */
    private fun read(): List<Snippet> {
        val text = file.read() ?: return emptyList()
        return runCatching { WireJson.decodeFromString(LIST, text) }.getOrElse {
            Log.w(TAG, "snippets.json does not parse; kept as a copy")
            if (corruptCopy.read() == null) corruptCopy.write(text)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "SnippetStore"
        val LIST = ListSerializer(Snippet.serializer())
    }
}
