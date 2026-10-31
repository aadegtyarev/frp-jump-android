package ru.adsrv.frpjump.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A folder as read: its physical path (links resolved) and its entries. */
data class Folder(val path: String, val entries: List<RemoteEntry>)

/**
 * Reads folder [path] (`.` is the home folder) at its physical path, so a
 * folder entered through a link shows where it really is and relative link
 * targets in it resolve right. Links are looked into [parallel] at a time:
 * each costs two requests, and `/etc` has dozens.
 */
suspend fun readFolder(fs: RemoteFs, path: String, parallel: Int = LINKS_AT_ONCE): Folder = coroutineScope {
    val gate = Semaphore(parallel)
    val dir = async(Dispatchers.IO) { fs.canonicalize(path) }.await()
    val raw = async(Dispatchers.IO) { fs.list(dir) }.await()
    val entries = raw.filter { it.first != "." && it.first != ".." }.map { (name, attrs) ->
        async(Dispatchers.IO) {
            val at = childOf(dir, name)
            if (attrs.kind == NodeKind.LINK) {
                gate.withPermit { linkEntry(fs, dir, name, attrs) }
            } else {
                RemoteEntry(name, at, typeOf(attrs), attrs.size, attrs.modified, mode = attrs.mode, uid = attrs.uid, gid = attrs.gid)
            }
        }
    }.awaitAll()
    Folder(dir, entries)
}

/**
 * A symlink's row: its target's type, size and time. Broken only when
 * nothing is at the end of the chain; a target it may not look at, or a
 * loop, is "other" but not broken (the device may still serve it).
 */
private fun linkEntry(fs: RemoteFs, dir: String, name: String, attrs: RemoteAttrs): RemoteEntry {
    val at = childOf(dir, name)
    var broken = false
    val target = try {
        fs.stat(at)
    } catch (e: RemoteRefusal) {
        broken = e.reason == Refusal.NO_SUCH
        null
    }
    val raw = try {
        fs.readlink(at)
    } catch (_: RemoteRefusal) {
        ""
    }
    return RemoteEntry(
        name = name,
        path = at,
        type = target?.let(::typeOf) ?: EntryType.OTHER,
        size = target?.size ?: 0,
        modified = target?.modified ?: attrs.modified,
        link = if (raw.isEmpty()) LinkTarget("", at, broken) else linkTarget(at, raw, broken),
        mode = target?.mode,
        uid = target?.uid,
        gid = target?.gid,
    )
}

private fun typeOf(attrs: RemoteAttrs): EntryType = when (attrs.kind) {
    NodeKind.DIR -> EntryType.DIR
    NodeKind.FILE -> EntryType.FILE
    else -> EntryType.OTHER
}

private const val LINKS_AT_ONCE = 8
