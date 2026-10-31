package ru.adsrv.frpjump.notices

/** What a notice says by its glyph: something done, something to mind, or plain news. */
enum class NoticeKind { DONE, WARN, INFO }

/** A button on a notice ("Open", "Undo"); tapping it also hides the notice. */
class NoticeAction(val label: String, val run: () -> Unit)

/**
 * One message at the top of the app. [key] names what it is about: a
 * newer notice with the same key takes its place instead of waiting
 * behind it (a second "copied" replaces the first). A [first] one (Undo,
 * whose time counts) goes before the one shown and is never dropped.
 */
data class TopNotice(
    val id: Long,
    val text: String,
    val kind: NoticeKind = NoticeKind.INFO,
    val long: Boolean = false,
    val key: String? = null,
    val action: NoticeAction? = null,
    val first: Boolean = false,
) {
    /** How long it stays: about 2.5 s, 4 s when [long], longer with a button to reach. */
    val durationMs: Long
        get() = when {
            action != null -> ACTION_MS
            long -> LONG_MS
            else -> SHORT_MS
        }

    companion object {
        const val SHORT_MS = 2_500L
        const val LONG_MS = 4_000L
        const val ACTION_MS = 6_000L
    }
}

/**
 * The notices waiting to be shown, the first one shown now. Pure: the
 * caller keeps it behind one lock. A notice with the [TopNotice.key] or
 * the text of one already there replaces it in place; at most [limit]
 * wait, the oldest waiting one goes first. A [TopNotice.first] one is put
 * in front (the one shown waits behind it) and is never the one dropped.
 */
data class NoticeQueue(val items: List<TopNotice> = emptyList(), val limit: Int = MAX_WAITING) {
    val shown: TopNotice? get() = items.firstOrNull()

    fun post(notice: TopNotice): NoticeQueue {
        val same = { it: TopNotice -> (notice.key != null && it.key == notice.key) || it.text == notice.text }
        if (notice.first) return copy(items = listOf(notice) + items.filterNot(same)).trimmed()
        val at = items.indexOfFirst(same)
        if (at >= 0) return copy(items = items.toMutableList().also { it[at] = notice })
        return copy(items = items + notice).trimmed()
    }

    /** The one shown stays; the oldest of those waiting that is not [TopNotice.first] gives way. */
    private fun trimmed(): NoticeQueue {
        if (items.size <= limit + 1) return this
        val drop = items.drop(1).indexOfFirst { !it.first }
        return if (drop < 0) this else copy(items = items.filterIndexed { i, _ -> i != drop + 1 })
    }

    /** [id] hidden (its time is up, a swipe or a tap); the next one shows. */
    fun dismiss(id: Long): NoticeQueue = copy(items = items.filterNot { it.id == id })

    /** The notice about [key] hidden, shown or waiting (an Undo whose time is up). */
    fun dismissKey(key: String): NoticeQueue = copy(items = items.filterNot { it.key == key })

    companion object {
        const val MAX_WAITING = 3
    }
}
