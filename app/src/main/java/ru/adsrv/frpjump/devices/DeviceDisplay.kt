package ru.adsrv.frpjump.devices

import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import java.text.Normalizer

/** The server's cap on a label: code points after NFC and trimming. */
const val LABEL_MAX = 64

/** How many code points of one insertion are looked at, at most: NFC never folds more than a few into one. */
private const val INSERT_LOOK = LABEL_MAX * 4

private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

/** What the counter shows: code points after NFC, as the server counts. */
fun labelLength(text: String): Int = nfc(text).let { it.codePointCount(0, it.length) }

/** The label the server would store for [text]: NFC, trimmed; null when nothing is left (clears it). */
fun labelToSend(text: String): String? = nfc(text).trim().ifEmpty { null }

/** The label changed: [field] (NFC, trimmed) differs from [current]; a trailing space alone changes nothing. */
fun labelChanged(field: String, current: String?): Boolean = labelToSend(field) != current?.ifBlank { null }

/** Line breaks and tabs in what was pasted or typed become spaces: the label is one line. */
fun oneLine(text: String): String = text.replace("\r\n", " ").map { if (it in LINE_BREAKS) ' ' else it }.joinToString("")

private val LINE_BREAKS = setOf('\n', '\r', '\t', '\u000B', '\u000C', '\u0085', ' ', ' ')

/** The field after an edit: its text and where the cursor goes. */
data class LabelEdit(val text: String, val cursor: Int)

/**
 * The field's text after [old] became [new] (typed or pasted): one line,
 * and never past [LABEL_MAX]. Over the cap, the inserted part is cut so it
 * fits and the rest stays as it was: typing at the cap changes nothing, a
 * longer paste is cut at [LABEL_MAX] code points. [cursor] is where the
 * edit put the cursor; after a cut it goes to the end of what was kept.
 */
fun limitLabel(old: String, new: String, cursor: Int): LabelEdit {
    val flat = oneLine(new)
    if (labelLength(flat) <= LABEL_MAX) return LabelEdit(flat, cursor.coerceIn(0, flat.length))
    val before = oneLine(old)
    // The inserted part: what lies between the common start and the common end.
    var start = 0
    val shortest = minOf(before.length, flat.length)
    while (start < shortest && before[start] == flat[start]) start++
    var end = 0
    while (end < shortest - start && before[before.length - 1 - end] == flat[flat.length - 1 - end]) end++
    // Never split a surrogate pair at either edge.
    if (start > 0 && Character.isHighSurrogate(flat[start - 1])) start--
    if (end > 0 && Character.isLowSurrogate(flat[flat.length - end])) end--
    val head = flat.substring(0, start)
    val tail = flat.substring(flat.length - end)
    val inserted = flat.substring(start, flat.length - end)
    var take = minOf(inserted.codePointCount(0, inserted.length), INSERT_LOOK)
    while (take > 0) {
        val piece = inserted.substring(0, inserted.offsetByCodePoints(0, take))
        if (labelLength(head + piece + tail) <= LABEL_MAX) return LabelEdit(head + piece + tail, head.length + piece.length)
        take--
    }
    // Nothing of the insertion fits; the rest may itself be over (an old text): cut it at the cap.
    val kept = head + tail
    if (labelLength(kept) <= LABEL_MAX) return LabelEdit(kept, head.length)
    val normal = nfc(kept)
    val cut = normal.substring(0, normal.offsetByCodePoints(0, LABEL_MAX))
    return LabelEdit(cut, cut.length)
}

/** The icon tile picked in the sheet. */
sealed interface IconPick {
    /** No choice of the owner: the device's reported kind shows. */
    data object Auto : IconPick

    data class Known(val icon: DeviceIcon) : IconPick

    /** The owner's choice on the server is a value this app does not know; kept as is, never sent. */
    data class Other(val wire: String) : IconPick

    companion object {
        /** The tile for the owner's choice [icon] as the server has it. */
        fun of(icon: String?): IconPick = when {
            icon == null -> Auto
            else -> DeviceIcon.fromWire(icon)?.let(::Known) ?: Other(icon)
        }
    }
}

/** A new value for one field; [value] null clears it. */
data class DisplayValue(val value: String?)

/** What «Сохранить» sends: only the fields that changed; null when nothing did. */
data class DisplayChange(val label: DisplayValue?, val icon: DisplayValue?) {
    val empty: Boolean get() = label == null && icon == null
}

/**
 * The change from what was loaded ([currentLabel], [currentIcon]) to the
 * sheet's [field] and [pick]. «Другой» is only ever the loaded value, so it
 * is never sent.
 */
fun displayChange(field: String, pick: IconPick, currentLabel: String?, currentIcon: String?): DisplayChange {
    val label = if (labelChanged(field, currentLabel)) DisplayValue(labelToSend(field)) else null
    val icon = when (pick) {
        IconPick.Auto -> DisplayValue(null).takeIf { currentIcon != null }
        is IconPick.Known -> DisplayValue(pick.icon.wire).takeIf { currentIcon != pick.icon.wire }
        is IconPick.Other -> null
    }
    return DisplayChange(label, icon)
}

/** Why the server refused a label: its five fixed texts, matched whole. */
enum class LabelRefusal(val wire: String) {
    TOO_LONG_RAW("label is too long"),
    TOO_LONG("label is longer than 64 characters"),
    CONTROL("label contains a line break or control character"),
    INVISIBLE("label contains an invisible or formatting character"),
    NOT_VISIBLE("label has no visible characters"),
    ;

    companion object {
        fun of(detail: String): LabelRefusal? = entries.firstOrNull { it.wire == detail }
    }
}

/** Why a save failed, and so where the sheet says it. */
sealed interface DisplayFailure {
    /** Under the field, in the app's words. */
    data class Label(val refusal: LabelRefusal) : DisplayFailure

    /** Under the field, the server's text: a refusal of the label this app does not know. */
    data class LabelText(val text: String) : DisplayFailure

    /** This profile's phone is disabled on the server (403): under the button. */
    data object Disabled : DisplayFailure

    /** No connection to the server: under the button. */
    data object Unreachable : DisplayFailure

    /** Anything else: its text under the button. */
    data class Other(val text: String) : DisplayFailure
}

/**
 * Where and how the sheet reports [e], a failed save of [change]. A 400
 * is a refused label when it is one of the five known texts; an unknown
 * one goes under the field only when the label alone was sent.
 */
fun displayFailure(e: Throwable, change: DisplayChange, message: (Throwable) -> String): DisplayFailure = when {
    // Not BROKE: the answer was cut off, so the server may have saved it; its text says so.
    e is NetworkException && e.reason == NetworkException.Reason.UNREACHABLE -> DisplayFailure.Unreachable
    e is ApiException && e.status == 403 -> DisplayFailure.Disabled
    e is ApiException && e.status == 400 -> LabelRefusal.of(e.detail)?.let { DisplayFailure.Label(it) }
        ?: if (change.label != null && change.icon == null) DisplayFailure.LabelText(e.detail) else DisplayFailure.Other(e.detail)
    else -> DisplayFailure.Other(message(e))
}
