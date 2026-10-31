package ru.adsrv.frpjump.files.text

/** The largest file the viewer takes; a larger one goes to another app. */
const val MAX_VIEW_BYTES = 2L * 1024 * 1024

/** How much of a file's start [looksLikeText] reads. */
const val SNIFF_BYTES = 8 * 1024

/** What Open does with a file, before anything is downloaded. */
enum class OpenRoute {
    /** A name known as text: download, check the start as for [SNIFF] (a name can lie), then the viewer. */
    VIEW,

    /** A name that says nothing: download, look at the start ([viewable]), then the viewer or another app. */
    SNIFF,

    /** A name known as binary: another app at once. */
    OTHER_APP,

    /** Not binary by name, larger than [MAX_VIEW_BYTES]: another app, saying why. */
    TOO_LARGE,
}

/** Extensions of text without a colouring of their own ([formatByName] covers the rest). */
private val TEXT_EXTENSIONS = setOf(
    "txt", "text", "csv", "tsv", "xml", "html", "htm", "css", "svg", "py", "rb", "pl", "lua", "c", "h", "cpp", "hpp",
    "go", "rs", "java", "kt", "kts", "ts", "tsx", "jsx", "sql", "diff", "patch", "rules", "list", "lst", "pem", "crt",
    "pub", "out", "err", "rst", "adoc", "tex", "gradle", "mk", "cmake", "dts", "dtsi", "template",
)

/** Whole names of text files without a telling extension. */
private val TEXT_NAMES = setOf(
    "readme", "license", "changelog", "makefile", "dockerfile", "hosts", "fstab", "passwd", "group", "crontab",
    "resolv.conf", "interfaces", "hostname", "motd", "issue", "authorized_keys", "known_hosts", "config",
)

/** Extensions another app should open: images, media, documents, archives, binaries. */
private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "tif", "tiff",
    "mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "mp4", "mkv", "avi", "mov", "webm", "3gp",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub",
    "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "deb", "rpm", "apk", "jar", "cpio", "lz4", "lzma",
    "bin", "img", "iso", "so", "o", "a", "ko", "elf", "exe", "dll", "class", "pyc",
    "db", "sqlite", "sqlite3", "wbfw", "fw", "ttf", "otf", "woff", "woff2",
)

/** What Open does with a file named [name] of [size] bytes. */
fun openRoute(name: String, size: Long): OpenRoute = when {
    extension(name) in BINARY_EXTENSIONS -> OpenRoute.OTHER_APP
    size > MAX_VIEW_BYTES -> OpenRoute.TOO_LARGE
    isTextName(name) -> OpenRoute.VIEW
    else -> OpenRoute.SNIFF
}

/** Whether [name] alone says the file is text, ignoring case. */
fun isTextName(name: String): Boolean =
    formatByName(name) != null || extension(name) in TEXT_EXTENSIONS || name.lowercase() in TEXT_NAMES

/** [name]'s last extension in lower case; none for `.bashrc` or `notes`. */
private fun extension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot + 1).lowercase() else ""
}

/**
 * Whether the first [length] bytes of [bytes] read as text: no NUL byte,
 * and at most one in 50 bytes a control character (other than tab, line
 * ends, form feed and escape) or a broken UTF-8 sequence. A sequence cut
 * at the end of the sample is not counted.
 */
fun looksLikeText(bytes: ByteArray, length: Int = bytes.size): Boolean {
    val n = minOf(length, bytes.size)
    var bad = 0
    var i = 0
    while (i < n) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b == 0 -> return false
            b < 0x20 -> {
                if (b != 9 && b != 10 && b != 13 && b != 12 && b != 27) bad++
                i++
            }
            b < 0x80 -> i++
            else -> {
                val follow = when {
                    b in 0xC2..0xDF -> 1
                    b in 0xE0..0xEF -> 2
                    b in 0xF0..0xF4 -> 3
                    else -> -1
                }
                if (follow < 0) {
                    bad++
                    i++
                } else if (i + follow >= n) {
                    // Cut by the sample's end: not counted either way.
                    break
                } else if ((1..follow).all { (bytes[i + it].toInt() and 0xC0) == 0x80 }) {
                    i += follow + 1
                } else {
                    bad++
                    i++
                }
            }
        }
    }
    return bad * 50 <= n
}

/** Whether a downloaded file goes to the viewer. */
enum class Viewable { YES, TOO_LARGE, BINARY }

/**
 * The viewer's verdict on a downloaded file of [size] bytes whose first
 * [length] bytes are [head]: over [MAX_VIEW_BYTES] (it grew since it was
 * listed) or not [looksLikeText] goes to another app.
 */
fun viewable(size: Long, head: ByteArray, length: Int): Viewable = when {
    size > MAX_VIEW_BYTES -> Viewable.TOO_LARGE
    !looksLikeText(head, length) -> Viewable.BINARY
    else -> Viewable.YES
}

/** Names whose text is likely a secret: `.env` files, keys, Wi-Fi connections, password files. */
fun looksSecret(name: String): Boolean {
    val lower = name.lowercase()
    return lower == ".env" || lower.startsWith(".env.") || lower.endsWith(".env") ||
        lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".nmconnection") ||
        lower in setOf("shadow", "gshadow", "credentials", "id_rsa", "id_ed25519", "id_ecdsa")
}
