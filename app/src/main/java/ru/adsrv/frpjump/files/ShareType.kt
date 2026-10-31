package ru.adsrv.frpjump.files

/**
 * The type one share of several files declares: their type when they all
 * have one, the family with a wildcard subtype (images of several kinds)
 * when they share it, else any type at all.
 */
fun shareType(types: List<String>): String {
    val distinct = types.distinct()
    distinct.singleOrNull()?.let { return it }
    val family = distinct.map { it.substringBefore('/') }.distinct().singleOrNull()
    return if (family != null && family != "*") "$family/*" else "*/*"
}
