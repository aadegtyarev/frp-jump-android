package ru.adsrv.frpjump.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What an entry is, or for a symlink what its target is. */
@Serializable
enum class EntryType {
    @SerialName("dir") DIR,
    @SerialName("file") FILE,

    /** A device, socket, pipe, or a link whose target is missing. */
    @SerialName("other") OTHER,
}

/** A symlink: what it points to, as written and as an absolute path, and whether that exists. */
@Serializable
data class LinkTarget(
    /** The link's text (`readlink`), e.g. `../data/etc`. */
    @SerialName("raw") val raw: String,
    /** [raw] made absolute against the link's folder and normalised, e.g. `/mnt/data/etc`. */
    @SerialName("path") val path: String,
    @SerialName("broken") val broken: Boolean = false,
)

/** One row of a folder listing; for a symlink [type], [size] and [modified] are its target's. */
@Serializable
data class RemoteEntry(
    @SerialName("name") val name: String,
    @SerialName("path") val path: String,
    @SerialName("type") val type: EntryType,
    @SerialName("size") val size: Long = 0,
    /** Seconds since the epoch, as SFTP gives it. */
    @SerialName("modified") val modified: Long = 0,
    @SerialName("link") val link: LinkTarget? = null,
) {
    val hidden: Boolean get() = name.startsWith(".")
    val isDir: Boolean get() = type == EntryType.DIR

    /**
     * Whether it can be downloaded: a file, or a link whose target the app
     * could not look at (no permission, a loop) but which is not broken;
     * the device then says itself whether it serves it.
     */
    val transferable: Boolean get() = type == EntryType.FILE || (link != null && !link.broken && type == EntryType.OTHER)

    /** Where a tap on a folder (or a link to one) leads: a link's target, a physical path. */
    val folderPath: String get() = link?.takeIf { !it.broken }?.path ?: path
}

/**
 * A listing as the screen shows it: dot names only when [showHidden],
 * folders (and links to folders) first, then the rest, each by name
 * ignoring case.
 */
fun visibleListing(entries: List<RemoteEntry>, showHidden: Boolean): List<RemoteEntry> =
    entries.filter { showHidden || !it.hidden }
        .sortedWith(compareBy<RemoteEntry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name })

/**
 * A symlink at [linkPath] whose text is [raw]: its target made absolute
 * against the link's folder, which must be a physical path for a relative
 * [raw] to resolve right ([readFolder] gives one). [broken]: nothing there.
 */
fun linkTarget(linkPath: String, raw: String, broken: Boolean): LinkTarget =
    LinkTarget(raw, resolvePath(parentOf(linkPath), raw), broken)

/** "→ /mnt/data/etc", as a link's row shows its target. */
fun linkLabel(target: LinkTarget): String = "→ ${target.path}"

/**
 * Where "Go to the target" leads: the target itself when it is a folder,
 * else the folder it is in (a broken link's target folder may still exist).
 */
fun targetFolder(entry: RemoteEntry): String? {
    val link = entry.link ?: return null
    return if (entry.isDir && !link.broken) link.path else parentOf(link.path)
}
