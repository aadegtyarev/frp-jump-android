package ru.adsrv.frpjump.files

/** One part of the path bar: its label and the folder a tap on it opens. */
data class PathSegment(val label: String, val path: String)

/** `/mnt/data` as `/`, `mnt`, `data`, each with the folder it stands for. */
fun pathSegments(path: String): List<PathSegment> {
    val parts = normalizePath(path).split('/').filter { it.isNotEmpty() }
    val segments = mutableListOf(PathSegment("/", "/"))
    var at = ""
    parts.forEach { part ->
        at = "$at/$part"
        segments += PathSegment(part, at)
    }
    return segments
}

/** The folder [path] is in; `/` for `/` itself. */
fun parentOf(path: String): String {
    val clean = normalizePath(path)
    val cut = clean.lastIndexOf('/')
    return if (cut <= 0) "/" else clean.substring(0, cut)
}

/** [name] in folder [dir]. */
fun childOf(dir: String, name: String): String = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

/** An absolute path without `.`, `..`, doubled or trailing slashes; `..` above `/` stays at `/`. */
fun normalizePath(path: String): String {
    val parts = ArrayDeque<String>()
    path.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(part)
        }
    }
    return "/" + parts.joinToString("/")
}

/**
 * What the owner typed in the path bar, or a link's text, as an absolute
 * path: relative to [base], `~` and `~/…` relative to [home] when known.
 */
fun resolvePath(base: String, typed: String, home: String? = null): String {
    val text = typed.trim()
    return when {
        text.startsWith("/") -> normalizePath(text)
        home != null && (text == "~" || text.startsWith("~/")) -> normalizePath(home + text.removePrefix("~"))
        else -> normalizePath("$base/$text")
    }
}
