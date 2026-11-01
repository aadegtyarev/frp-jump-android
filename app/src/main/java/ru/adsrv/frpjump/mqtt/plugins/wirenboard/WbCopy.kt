package ru.adsrv.frpjump.mqtt.plugins.wirenboard

/** What copying a control's value puts on the clipboard, or why nothing. */
sealed interface WbValueCopy {
    /** The status topic's payload as published (no rounding, units or enum title). */
    data class Text(val text: String) : WbValueCopy

    /** A read error (`r`): the shown «—» stands for no value, so nothing is copied. */
    data object Unreadable : WbValueCopy

    /** No value seen yet. */
    data object NoValue : WbValueCopy

    /** The published value is empty. */
    data object Empty : WbValueCopy
}

fun valueCopy(control: WbControl): WbValueCopy = when {
    control.valueUnknown -> WbValueCopy.Unreadable
    control.value == null -> WbValueCopy.NoValue
    control.value.isEmpty() -> WbValueCopy.Empty
    else -> WbValueCopy.Text(control.value)
}

/**
 * The control in Wiren Board's short form, `<device>/<control>`
 * (`wb-map6s_34/Irms 1`), as wb-rules and the web UI name it; a topic not
 * of the convention's shape is given whole.
 */
fun controlShortName(control: WbControl): String {
    val levels = control.path.levels
    val at = levels.size - 4
    return if (at >= 0 && levels[at] == "devices" && levels[at + 2] == "controls") "${levels[at + 1]}/${levels[at + 3]}" else control.path.format()
}

/** The control's full MQTT topic (`/devices/wb-map6s_34/controls/Irms 1`). */
fun controlTopic(control: WbControl): String = control.path.format()
