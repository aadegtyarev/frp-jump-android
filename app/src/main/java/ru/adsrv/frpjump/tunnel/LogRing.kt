package ru.adsrv.frpjump.tunnel

/** The last [capacity] frpc log lines, in memory only. */
class LogRing(private val capacity: Int = 500) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
