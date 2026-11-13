package ru.adsrv.frpjump.assistant

/**
 * The assistant's one notification per attempt (Р32), as a decision: which of its fixed texts to show. It has no content: no reply text,
 * tool names or targets, only the title ([titleRes]) and, while it is ongoing, the action Stop.
 */
enum class AssistantNotice(val ongoing: Boolean, val titleRes: Int) {
    WORKING(true, ru.adsrv.frpjump.R.string.assistant_notice_working),
    WAITING(true, ru.adsrv.frpjump.R.string.assistant_notice_waiting),
    ANSWERED(false, ru.adsrv.frpjump.R.string.assistant_notice_answered),
    FAILED(false, ru.adsrv.frpjump.R.string.assistant_notice_failed),
    UNANSWERED(false, ru.adsrv.frpjump.R.string.assistant_notice_unanswered),
}

/**
 * What to show now, or null for nothing (and the notification is removed). Nothing without the notification permission or while the chat
 * is [shown]. While an attempt runs it is the ongoing working or waiting one; when it has ended, the text for [end] (NONE after the owner's
 * Stop or a disable: no final notification).
 */
fun assistantNotice(activity: ChatActivity, shown: Boolean, end: ChatEnd, permission: Boolean): AssistantNotice? {
    if (!permission || shown) return null
    return when (activity) {
        ChatActivity.WORKING -> AssistantNotice.WORKING
        ChatActivity.WAITING -> AssistantNotice.WAITING
        ChatActivity.IDLE -> when (end) {
            ChatEnd.NONE -> null
            ChatEnd.COMPLETED -> AssistantNotice.ANSWERED
            ChatEnd.FAILED -> AssistantNotice.FAILED
            ChatEnd.UNANSWERED -> AssistantNotice.UNANSWERED
        }
    }
}

/**
 * Remembers whether an attempt ended while the chat was off screen: an end that happened with the chat shown is not announced when the
 * owner leaves it afterwards. Feed every change of the four inputs to [update].
 */
class AssistantNoticeTracker {
    private var before = ChatActivity.IDLE
    private var endedOffScreen = false

    fun update(activity: ChatActivity, shown: Boolean, end: ChatEnd, permission: Boolean): AssistantNotice? {
        if (activity != ChatActivity.IDLE) endedOffScreen = false
        else if (before != ChatActivity.IDLE) endedOffScreen = !shown
        if (shown) endedOffScreen = false
        before = activity
        return assistantNotice(activity, shown, if (endedOffScreen) end else ChatEnd.NONE, permission)
    }
}
