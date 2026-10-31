package ru.adsrv.frpjump.files

/** What a row's icon says an entry is, at a glance; a link keeps its target's kind (its badge says "link"). */
enum class FileKind {
    FOLDER,
    FILE,

    /** A file with any execute bit. */
    EXECUTABLE,

    /** A file named as an archive or compressed file. */
    ARCHIVE,

    /** A device, socket or pipe, or a link whose target the app could not look at. */
    SPECIAL,

    /** A link whose target does not exist. */
    BROKEN_LINK,
}

/** Last extensions that make a name an archive; any `.tar.*` is one too. */
private val ARCHIVE_EXTENSIONS = setOf(
    "zip", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "zst", "tzst",
    "lz", "lz4", "lzma", "7z", "rar", "deb", "rpm", "cpio", "z",
)

/** Whether [name] reads as an archive, ignoring case: `a.zip`, `a.TAR.GZ`, `a.tar.br`; not `.gz` alone. */
fun isArchiveName(name: String): Boolean {
    val lower = name.lowercase()
    val dot = lower.lastIndexOf('.')
    if (dot <= 0) return false
    return lower.substring(dot + 1) in ARCHIVE_EXTENSIONS || lower.indexOf(".tar.") > 0
}

/**
 * [entry]'s kind for its icon: a folder (or a link to one) is a folder
 * whatever its name; a file is an archive by name before it is executable
 * by mode, as card and FAT mounts mark every file executable.
 */
fun fileKind(entry: RemoteEntry): FileKind = when {
    entry.link?.broken == true -> FileKind.BROKEN_LINK
    entry.type == EntryType.DIR -> FileKind.FOLDER
    entry.type == EntryType.OTHER -> FileKind.SPECIAL
    isArchiveName(entry.name) -> FileKind.ARCHIVE
    ((entry.mode ?: 0) and 0b001_001_001) != 0 -> FileKind.EXECUTABLE
    else -> FileKind.FILE
}
