package ru.adsrv.frpjump.files

import java.security.MessageDigest

/** Endings kept whole when a number goes before the extension: `backup (1).tar.gz`. */
private val DOUBLE_EXTENSIONS = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst")

/**
 * [name] if [taken] says it is free, else "Keep both": `name (1).ext`,
 * `name (2).ext`, … A dot file (`.bashrc`) has no extension: `.bashrc (1)`.
 */
fun keepBothName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val (stem, extension) = splitExtension(name)
    var n = 1
    while (true) {
        val candidate = "$stem ($n)$extension"
        if (!taken(candidate)) return candidate
        n++
    }
}

/** `report.pdf` as `report` and `.pdf`; `.bashrc` and `README` have no extension. */
fun splitExtension(name: String): Pair<String, String> {
    DOUBLE_EXTENSIONS.firstOrNull { name.length > it.length && name.endsWith(it, ignoreCase = true) }?.let {
        return name.dropLast(it.length) to name.takeLast(it.length)
    }
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot)
}

private const val PART_SUFFIX = ".frpjump-part"

/** The longest file name most Linux file systems take, in bytes. */
const val MAX_NAME_BYTES = 255

/**
 * The name an upload is written under before it is renamed to [name], so
 * a half upload never replaces a file: `.name.frpjump-part`. A long [name]
 * is cut (whole characters) to stay within [MAX_NAME_BYTES], with a short
 * hash of it so two long names do not share a part file.
 */
fun uploadPartName(name: String): String {
    val plain = ".$name$PART_SUFFIX"
    if (plain.utf8Size() <= MAX_NAME_BYTES) return plain
    val tag = "~" + MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }
    val budget = MAX_NAME_BYTES - 1 - tag.utf8Size() - PART_SUFFIX.utf8Size()
    val cut = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        val size = String(Character.toChars(cp)).utf8Size()
        if (bytes + size > budget) break
        cut.appendCodePoint(cp)
        bytes += size
        i += Character.charCount(cp)
    }
    return ".$cut$tag$PART_SUFFIX"
}

/**
 * A device's file name made safe for a file in the app's cache: no `/` or
 * NUL, never `.`, `..` or empty.
 */
fun safeLocalName(name: String): String {
    val clean = name.replace('/', '_').replace('\u0000', '_')
    return if (clean.isBlank() || clean == "." || clean == "..") "file" else clean
}

private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
