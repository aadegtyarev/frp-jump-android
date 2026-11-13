package ru.adsrv.frpjump.assistant.chat

/**
 * Pure projection of the saved document onto [previous]. [projected] are the document's already viewed turns;
 * [archive] is whether a history store exists; [running] is whether a run owner is pending.
 */
internal fun projectChat(value: SavedChat, projected: List<ChatTurn>, archive: Boolean, running: Boolean, previous: ChatState): ChatState {
    val last = value.records.lastOrNull()
    val current = last?.takeIf { it.status == SavedTurnState.RUNNING || (!archive && it.status != SavedTurnState.COMPLETE) }
    val currentView = if (current == null) null else projected.lastOrNull()
    // An interrupted last attempt is told once, by the grey line under its turn; no red notice repeats it.
    val problem = last?.problem?.let { ChatProblem.valueOf(it) }
    return previous.copy(
        turns = if (!archive) projected.filterIndexed { index, _ -> value.records[index].status == SavedTurnState.COMPLETE }
            else if (current == null) projected else projected.dropLast(1),
        draft = value.draft, pendingText = current?.takeIf { it.status == SavedTurnState.RUNNING && running }?.user,
        problem = problem, omitted = value.omitted, contextChanged = value.contextStart > 0,
        summarized = value.summary != null, problemStatus = last?.problemStatus,
        currentSources = currentView?.sources.orEmpty(), receipts = currentView?.receipts.orEmpty(),
        toolFailed = currentView?.toolFailed ?: false, toolFailures = currentView?.toolFailures.orEmpty(), information = currentView?.information.orEmpty())
}
