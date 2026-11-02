package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An in-memory editor value; no saved-state or persistence representation. */
data class TerminalDraft(
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = selectionStart,
    val textMode: Boolean = false,
    val expanded: Boolean = false,
    val revision: Long = 0,
)

/** The exact text and destination captured when Insert was pressed. */
data class DraftSubmission(val sessionId: Int, val revision: Long, val text: String)

/** Owned by SSH sessions, not by a screen or keyboard connection. Main thread only. */
class TerminalDrafts {
    private val values = MutableStateFlow<Map<Int, TerminalDraft>>(emptyMap())
    val state: StateFlow<Map<Int, TerminalDraft>> = values
    private var nextRevision = 1L

    fun open(id: Int) {
        if (id !in values.value) values.value = values.value + (id to TerminalDraft(revision = nextRevision++))
    }

    fun close(id: Int) {
        values.value = values.value - id
    }

    fun edit(id: Int, text: String, selectionStart: Int, selectionEnd: Int) = change(id) { old ->
        old.copy(
            text = text,
            selectionStart = selectionStart.coerceIn(0, text.length),
            selectionEnd = selectionEnd.coerceIn(0, text.length),
            revision = if (text == old.text) old.revision else nextRevision++,
        )
    }

    fun textMode(id: Int, enabled: Boolean) = change(id) { it.copy(textMode = enabled, expanded = if (enabled) it.expanded else false) }

    fun expand(id: Int, expanded: Boolean) = change(id) { it.copy(textMode = true, expanded = expanded) }

    fun submission(id: Int): DraftSubmission? = values.value[id]?.let { DraftSubmission(id, it.revision, it.text) }

    /** Never erase an edit made while a multiline confirmation was open. */
    fun inserted(submission: DraftSubmission) = change(submission.sessionId) { old ->
        if (old.revision != submission.revision) old else old.copy(text = "", selectionStart = 0, selectionEnd = 0, revision = nextRevision++)
    }

    private fun change(id: Int, update: (TerminalDraft) -> TerminalDraft) {
        val old = values.value[id] ?: return
        val next = update(old)
        if (next != old) values.value = values.value + (id to next)
    }
}
