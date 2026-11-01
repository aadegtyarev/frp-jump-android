package ru.adsrv.frpjump.update

/**
 * A version as the app and its release tags write it: "0.2.1", "v0.2.0-beta2".
 * Ordered as semver: a pre-release comes before its release
 * (0.2.0-beta1 < 0.2.0-beta2 < 0.2.0). Pre-release parts compare as semver
 * says, except that letters followed by a number compare by that number,
 * so beta10 comes after beta2. Build metadata ("+abc") is ignored.
 */
class AppVersion private constructor(
    private val core: List<Int>,
    private val pre: List<String>,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until CORE_PARTS) {
            core[i].compareTo(other.core[i]).let { if (it != 0) return it }
        }
        // A release is newer than any of its pre-releases.
        if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            comparePart(pre[i], other.pre[i]).let { if (it != 0) return it }
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString() = core.joinToString(".") + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private const val CORE_PARTS = 3
        private val CORE = Regex("""\d{1,9}""")
        private val PRE = Regex("""[0-9A-Za-z-]+""")
        private val RUNS = Regex("""\d+|\D+""")

        /** Null when [text] is not a version ("v" in front is allowed: tags are "v0.2.1"). */
        fun parse(text: String): AppVersion? {
            val bare = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val coreText = bare.substringBefore('-')
            val preText = if ('-' in bare) bare.substringAfter('-') else null
            val core = coreText.split('.')
            if (core.size !in 1..CORE_PARTS || core.any { !CORE.matches(it) }) return null
            val pre = preText?.split('.') ?: emptyList()
            if (pre.any { !PRE.matches(it) }) return null
            return AppVersion(core.map { it.toInt() } + List(CORE_PARTS - core.size) { 0 }, pre)
        }

        /** Numbers by value and before words; words by their runs of letters and of digits ("beta2" < "beta10"). */
        private fun comparePart(a: String, b: String): Int {
            val an = a.toBigIntegerOrNull()
            val bn = b.toBigIntegerOrNull()
            if (an != null && bn != null) return an.compareTo(bn)
            if (an != null) return -1
            if (bn != null) return 1
            val ar = RUNS.findAll(a).map { it.value }.toList()
            val br = RUNS.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(ar.size, br.size)) {
                val x = ar[i]
                val y = br[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
                if (c != 0) return c
            }
            return ar.size.compareTo(br.size)
        }
    }
}

/** Whether [candidate] is a later version than [installed]; false when either is not a version. */
fun isNewer(candidate: String, installed: String): Boolean {
    val a = AppVersion.parse(candidate) ?: return false
    val b = AppVersion.parse(installed) ?: return false
    return a > b
}
