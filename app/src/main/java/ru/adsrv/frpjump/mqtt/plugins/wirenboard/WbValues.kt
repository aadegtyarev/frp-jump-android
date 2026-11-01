package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The units of the deprecated typed controls, as on the wire (ASCII). */
val TYPED_UNITS: Map<String, String> = mapOf(
    "temperature" to "deg C",
    "rel_humidity" to "%, RH",
    "atmospheric_pressure" to "mbar",
    "rainfall" to "mm/h",
    "wind_speed" to "m/s",
    "power" to "W",
    "power_consumption" to "kWh",
    "voltage" to "V",
    "water_flow" to "m^3/h",
    "water_consumption" to "m^3",
    "resistance" to "Ohm",
    "concentration" to "ppm",
    "heat_power" to "Gcal/h",
    "heat_energy" to "Gcal",
    "current" to "A",
    "pressure" to "bar",
    "lux" to "lx",
    "sound_level" to "dB",
)

private val DEG = Regex("\\bdeg\\b")

/** ASCII units drawn with symbols: `deg C` °C, `deg` °, `%, RH` %RH, `^3` ³, `Ohm` Ω (`mOhm` mΩ); the rest as is. */
fun unitsSymbols(ascii: String): String = ascii
    .replace("deg C", "°C")
    .replace(DEG, "°")
    .replace("%, RH", "%RH")
    .replace("^3", "³")
    .replace("Ohm", "Ω")

/** The units a control shows: its meta's `units`, else those of its deprecated type; drawn with symbols. */
fun unitsOf(meta: ControlMeta): String? = (meta.units ?: TYPED_UNITS[meta.type])?.let(::unitsSymbols)

/** Decimals [precision] has: `0.01` 2, `1e-06` 6, `0.5` 1, `1` and `10` 0. */
fun decimalsOf(precision: BigDecimal): Int = maxOf(0, precision.stripTrailingZeros().scale())

/**
 * [value] rounded to the nearest multiple of [precision] (half away from
 * zero), with as many decimals as [precision] has; in decimal arithmetic.
 */
fun roundToPrecision(value: BigDecimal, precision: BigDecimal): BigDecimal {
    val steps = value.divide(precision, 0, RoundingMode.HALF_UP)
    return (BigDecimal(steps.toBigInteger()) * precision).setScale(decimalsOf(precision), RoundingMode.HALF_UP)
}

/** A number as written in a value or an enum key: decimal, or hex with `0x` when [hex]; null for anything else. */
fun numberOf(text: String, hex: Boolean = false): BigDecimal? {
    val t = text.trim()
    if (hex) {
        val negative = t.startsWith("-")
        val body = t.removePrefix("-")
        if (body.startsWith("0x") || body.startsWith("0X")) {
            val digits = body.substring(2)
            if (digits.isEmpty() || digits.length > 32) return null
            val n = runCatching { BigInteger(digits, 16) }.getOrNull() ?: return null
            return BigDecimal(if (negative) n.negate() else n)
        }
    }
    return decimalOf(t)
}

/** [raw] with the digits of [precision]; the value as received without a precision or when it is no number. */
fun valueDigits(raw: String, precision: BigDecimal?): String {
    if (precision == null) return raw
    val n = numberOf(raw) ?: return raw
    return roundToPrecision(n, precision).toPlainString()
}

/** Types whose enum keys are numbers; any other type with an enum (`text`) matches them as strings. */
private val TEXT_TYPES = setOf("text", "w1-id")

/** Whether [meta]'s values are text rather than numbers (enum keys matched as strings). */
fun isTextType(meta: ControlMeta): Boolean = meta.type in TEXT_TYPES

/** The enum entry [raw] stands for: numerically for numeric types (decimal or hex keys), as strings for text. */
fun enumEntry(meta: ControlMeta, raw: String): WbEnumEntry? {
    if (meta.enum.isEmpty()) return null
    if (isTextType(meta)) return meta.enum.firstOrNull { it.key == raw }
    val n = numberOf(raw, hex = true) ?: return meta.enum.firstOrNull { it.key == raw }
    return meta.enum.firstOrNull { e -> numberOf(e.key, hex = true)?.let { it.compareTo(n) == 0 } == true }
}

private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** `local_time` seconds as a local date and time without the zone; null when it is not a number. */
fun localTimeText(raw: String, zone: ZoneId = ZoneId.systemDefault()): String? {
    val seconds = decimalOf(raw)?.setScale(0, RoundingMode.FLOOR)?.toBigInteger() ?: return null
    if (seconds.bitLength() > 40) return null
    return LOCAL_TIME.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone).toLocalDateTime())
}

/** What a control's value reads as: its enum title, its units apart. */
data class ShownValue(val text: String, val units: String?)

/**
 * [raw] as a control with [meta] shows it to a reader of [language]: the
 * enum title when the value has one (no units then); `local_time` as a
 * date and time; else the digits of `precision` and the units.
 */
fun shownValue(meta: ControlMeta, raw: String, language: String, zone: ZoneId = ZoneId.systemDefault()): ShownValue {
    enumEntry(meta, raw)?.let { e -> e.title.pick(language)?.let { return ShownValue(it, null) } }
    if (meta.type == "local_time") localTimeText(raw, zone)?.let { return ShownValue(it, null) }
    return ShownValue(valueDigits(raw, meta.precision), unitsOf(meta))
}
