package ru.adsrv.frpjump.ui.files

import ru.adsrv.frpjump.files.RemoteEntry

/**
 * The entries picked in one folder ([folder]) by their paths. Empty: not
 * selecting. A long press starts it, taps then add and remove, and it
 * ends when the last one is taken away, the folder changes, or Back.
 */
data class FileSelection(val folder: String? = null, val paths: Set<String> = emptySet()) {
    val active: Boolean get() = paths.isNotEmpty()
    val count: Int get() = paths.size

    operator fun contains(entry: RemoteEntry): Boolean = entry.path in paths

    /** A tap in selection mode (and the long press that starts it) in [folder]. */
    fun toggle(folder: String?, entry: RemoteEntry): FileSelection {
        val base = if (folder == this.folder) paths else emptySet()
        return FileSelection(folder, if (entry.path in base) base - entry.path else base + entry.path)
    }

    /** Every entry shown in [folder]. */
    fun selectAll(folder: String?, entries: List<RemoteEntry>): FileSelection = FileSelection(folder, entries.map { it.path }.toSet())

    /**
     * After the listing changed: another folder ends the selection, the
     * same one keeps only what is still shown.
     */
    fun keepIn(folder: String?, entries: List<RemoteEntry>): FileSelection {
        if (!active) return this
        if (folder != this.folder) return FileSelection()
        val shown = entries.mapTo(HashSet()) { it.path }
        return copy(paths = paths.filterTo(LinkedHashSet()) { it in shown })
    }

    /** The selected entries in the order [entries] shows them. */
    fun picked(entries: List<RemoteEntry>): List<RemoteEntry> = entries.filter { it.path in paths }
}

/**
 * What can be done to a selection. Stage 2 of the files contract adds
 * Copy, Move, Pack, Delete (bar) and Rename, Properties (its "⋮") here;
 * [selectionActions] says which apply.
 */
enum class SelectionAction { DOWNLOAD, SHARE }

/** The actions for [picked]: Download and Share while a file (not a folder) is among them. */
fun selectionActions(picked: List<RemoteEntry>): List<SelectionAction> =
    if (picked.any { it.transferable }) listOf(SelectionAction.DOWNLOAD, SelectionAction.SHARE) else emptyList()

/** The files of [picked] an action takes, and how many it leaves out (folders, broken links, devices). */
fun transferablePart(picked: List<RemoteEntry>): Pair<List<RemoteEntry>, Int> {
    val files = picked.filter { it.transferable }
    return files to picked.size - files.size
}
