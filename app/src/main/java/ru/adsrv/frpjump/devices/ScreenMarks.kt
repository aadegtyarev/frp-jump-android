package ru.adsrv.frpjump.devices

import java.util.concurrent.ConcurrentHashMap

/**
 * Which screens want something now (Devices and Connections want a device
 * list): each has its own mark, so one leaving does not take back what
 * another just asked for.
 */
class ScreenMarks {
    private val marks: MutableSet<Any> = ConcurrentHashMap.newKeySet()

    /** Some screen wants it. */
    val any: Boolean get() = marks.isNotEmpty()

    fun set(screen: Any, on: Boolean) {
        if (on) marks.add(screen) else marks.remove(screen)
    }
}
