package ru.adsrv.frpjump.assistant.chat

/** Pure edits of the one saved document. Callers apply them inside the same guarded sections as before. */

/** Replaces the last turn by [change]; [archive] histories are never trimmed. */
internal fun SavedChat.withLast(archive: Boolean, change: (SavedTurn) -> SavedTurn): SavedChat =
    copy(records = records.dropLast(1) + change(records.last())).bounded(archive)

internal fun SavedChat.withTurn(text: String, archive: Boolean): SavedChat = copy(records = records + SavedTurn(text)).bounded(archive)

/** Without an archive only completed turns survive into the next request. */
internal fun SavedChat.completedOnly(): SavedChat = copy(records = records.filter { it.status == SavedTurnState.COMPLETE })

internal fun SavedChat.interrupted(): SavedChat {
    val last = records.lastOrNull() ?: return this
    return if (last.status == SavedTurnState.RUNNING) copy(records = records.dropLast(1) + last.copy(status = SavedTurnState.INTERRUPTED)) else this
}

/** A successful finish also clears the draft. */
internal fun SavedChat.finished(problem: ChatProblem?, status: Int?, archive: Boolean): SavedChat {
    val last = records.last()
    return copy(records = records.dropLast(1) + last.copy(
        status = if (problem == null) SavedTurnState.COMPLETE else SavedTurnState.FAILED, problem = problem?.name, problemStatus = status),
        draft = if (problem == null) "" else draft).bounded(archive)
}

internal fun SavedChat.withSummary(value: SavedSummary): SavedChat = copy(summary = value)
internal fun SavedChat.withOmitted(): SavedChat = copy(omitted = true)

internal fun SavedChat.views(): List<ChatTurn> = records.map { it.view() }
