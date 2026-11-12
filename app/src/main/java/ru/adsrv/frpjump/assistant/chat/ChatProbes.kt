package ru.adsrv.frpjump.assistant.chat

/**
 * Narrow deterministic probes for the chat/store race regressions; the no-op [None] in production.
 * [before] runs outside the gate, immediately before a guarded commit; [checked] runs inside the gate after the ownership check.
 */
internal interface ChatProbes {
    fun before(category: String) {}
    fun checked(category: String) {}
    companion object None : ChatProbes
}

internal fun ChatProbes(before: (String) -> Unit = {}, checked: (String) -> Unit = {}): ChatProbes {
    val beforeAction = before; val checkedAction = checked
    return object : ChatProbes {
        override fun before(category: String) = beforeAction(category)
        override fun checked(category: String) = checkedAction(category)
    }
}
