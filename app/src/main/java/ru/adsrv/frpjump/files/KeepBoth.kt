package ru.adsrv.frpjump.files

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

/** The name an upload is written under before it is renamed to [name], so a half upload never replaces a file. */
fun uploadPartName(name: String): String = ".$name.frpjump-part"
