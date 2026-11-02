package ru.adsrv.frpjump.ssh

/** Local acceptance only, never an acknowledgement from a remote application. */
enum class PasteAttempt { INSERTED, UNAVAILABLE, FAILED }

/** A selection token changes even on switching away and back to the same tab. */
fun pasteExecutionSelected(captured: Any?, current: Any, destinationId: Int, selectedId: Int): Boolean =
    captured != null && captured === current && destinationId == selectedId

/** Revalidate a captured destination at dispatch; never retry or fall back to another tab. */
fun attemptPaste(available: () -> Boolean, paste: () -> Boolean): PasteAttempt {
    if (!available()) return PasteAttempt.UNAVAILABLE
    return try {
        if (paste()) PasteAttempt.INSERTED else PasteAttempt.UNAVAILABLE
    } catch (_: Exception) {
        PasteAttempt.FAILED
    }
}
