package ru.adsrv.frpjump.mqtt

/**
 * The order of the tree's rows by level name: the empty level first,
 * `$SYS` last, the rest with runs of ASCII digits compared by number
 * (`K2` before `K10`, any length, no overflow), other text ignoring case,
 * ties by exact text.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val byRank = rank(a).compareTo(rank(b))
        if (byRank != 0 || rank(a) != 1) return byRank
        val natural = compareNatural(a, b)
        return if (natural != 0) natural else a.compareTo(b)
    }

    /** Paths level by level in [NaturalOrder]; a path comes before the paths below it. */
    val paths: Comparator<MqttPath> = Comparator { a, b ->
        val common = minOf(a.levels.size, b.levels.size)
        for (k in 0 until common) {
            val c = compare(a.levels[k], b.levels[k])
            if (c != 0) return@Comparator c
        }
        a.levels.size.compareTo(b.levels.size)
    }

    private const val SYS = "\$SYS"

    private fun rank(level: String): Int = when (level) {
        "" -> 0
        SYS -> 2
        else -> 1
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (isDigit(a[i]) && isDigit(b[j])) {
                val aEnd = digitsEnd(a, i)
                val bEnd = digitsEnd(b, j)
                val aStart = significantStart(a, i, aEnd)
                val bStart = significantStart(b, j, bEnd)
                // More significant digits is a bigger number; equal lengths compare digit by digit.
                val byLength = (aEnd - aStart).compareTo(bEnd - bStart)
                if (byLength != 0) return byLength
                for (k in 0 until aEnd - aStart) {
                    val c = a[aStart + k].compareTo(b[bStart + k])
                    if (c != 0) return c
                }
                i = aEnd
                j = bEnd
            } else {
                val c = foldCase(a[i]).compareTo(foldCase(b[j]))
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && isDigit(s[end])) end++
        return end
    }

    /** Past leading zeros, keeping the last digit. */
    private fun significantStart(s: String, from: Int, end: Int): Int {
        var start = from
        while (start < end - 1 && s[start] == '0') start++
        return start
    }

    /** As [String.CASE_INSENSITIVE_ORDER] folds a character. */
    private fun foldCase(c: Char): Char = Character.toLowerCase(Character.toUpperCase(c))
}
