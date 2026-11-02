package ru.adsrv.frpjump.ui.connections

import java.text.NumberFormat
import java.util.Locale

data class DiagnosticBytes(val value: Double, val unit: Int)

fun scaleDiagnosticBytes(bytes: Double?): DiagnosticBytes? {
    if (bytes == null || !bytes.isFinite() || bytes < 0) return null
    var value = bytes
    var unit = 0
    while (value >= 1024 && unit < 3) { value /= 1024; unit++ }
    return DiagnosticBytes(value, unit)
}

fun diagnosticNumber(value: Double?, locale: Locale): String {
    if (value == null || !value.isFinite() || value < 0) return "—"
    val format = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = if (value < 10) 1 else 0
    }
    return if (value > 0 && value < 0.1) "<" + format.format(0.1) else format.format(value)
}
