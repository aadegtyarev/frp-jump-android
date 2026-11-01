package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.decodeUtf8
import java.math.BigDecimal
import java.math.RoundingMode

/** How a control is drawn and written, by its meta (docs/mqtt-contract.md, "Controls by type"). */
enum class WbWidget {
    /** `switch`: a toggle, on for `1`. */
    SWITCH,

    /** `wo-switch`: a toggle showing the last value sent in the session. */
    WO_SWITCH,

    /** `pushbutton`: a button with the title, sends `1`. */
    PUSHBUTTON,

    /** `range`: an integer slider, sent on release. */
    RANGE,

    /** `rgb`: a swatch opening a colour picker, sends `R;G;B`. */
    RGB,

    /** `alarm`: an indicator, red on `1`; never a control. */
    ALARM,

    /** An `enum`: a list of its titles. */
    ENUM,

    /** `value`, the typed controls and `local_time`: a numeric input. */
    NUMBER,

    /** `text`, `w1-id`: a text input. */
    TEXT,

    /** Read-only, no type or an unknown one: the value only. */
    READ_ONLY,
}

/** The default bounds of a `range`: 0 to 255 (the conventions' type section). */
val RANGE_DEFAULT_MIN: BigDecimal = BigDecimal.ZERO
val RANGE_DEFAULT_MAX: BigDecimal = BigDecimal(255)

private val NUMBER_TYPES = TYPED_UNITS.keys + setOf("value", "local_time")

/**
 * The widget of a control with [meta]. A read-only control shows its value
 * only, whatever the type; an alarm, never a control, keeps its indicator.
 */
fun widgetOf(meta: ControlMeta): WbWidget {
    val type = meta.type
    if (type == "alarm") return WbWidget.ALARM
    if (meta.readonly || type == null) return WbWidget.READ_ONLY
    return when (type) {
        "switch" -> WbWidget.SWITCH
        "wo-switch" -> WbWidget.WO_SWITCH
        "pushbutton" -> WbWidget.PUSHBUTTON
        "range" -> WbWidget.RANGE
        "rgb" -> WbWidget.RGB
        else -> when {
            (type in NUMBER_TYPES || type in setOf("text", "w1-id")) && meta.enum.isNotEmpty() -> WbWidget.ENUM
            type in NUMBER_TYPES -> WbWidget.NUMBER
            type == "text" || type == "w1-id" -> WbWidget.TEXT
            else -> WbWidget.READ_ONLY
        }
    }
}

/** A `switch` is on only for `1`; anything else (a missing value too) is off. */
fun switchOn(value: String?): Boolean = value?.trim() == "1"

/** What a toggle sends: `1` to turn on, `0` to turn off. */
fun switchPayload(on: Boolean): String = if (on) "1" else "0"

/** What a `pushbutton` sends. */
const val PUSH_PAYLOAD = "1"

/** A `range`'s bounds, integers: `min` (default 0) to `max` (default 255); an upside-down pair is put right. */
fun rangeBounds(meta: ControlMeta): IntRange {
    val lo = (meta.min ?: RANGE_DEFAULT_MIN).setScale(0, RoundingMode.CEILING).toInt()
    val hi = (meta.max ?: RANGE_DEFAULT_MAX).setScale(0, RoundingMode.FLOOR).toInt()
    return if (lo <= hi) lo..hi else hi..lo
}

/** A `range`'s value as a slider position in [bounds]; [bounds]' start when it is no number. */
fun rangePosition(value: String?, bounds: IntRange): Int {
    val n = value?.let { numberOf(it) } ?: return bounds.first
    return n.setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(bounds)
}

/** What a `range` sends: the integer, in decimal. */
fun rangePayload(value: Int): String = value.toString()

/** An `rgb` value's three integers 0–255 (`R;G;B`, spaces ignored); null when it is not one. */
fun rgbOf(value: String?): Triple<Int, Int, Int>? {
    val parts = value?.filterNot { it.isWhitespace() }?.split(';') ?: return null
    if (parts.size != 3) return null
    val n = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
    return Triple(n[0], n[1], n[2])
}

/** What an `rgb` sends: `R;G;B`, each clamped to 0–255. */
fun rgbPayload(r: Int, g: Int, b: Int): String = listOf(r, g, b).joinToString(";") { it.coerceIn(0, 255).toString() }

/** Why a typed number cannot be sent. */
sealed interface NumberProblem {
    data object NotANumber : NumberProblem

    data class BelowMin(val min: BigDecimal) : NumberProblem

    data class AboveMax(val max: BigDecimal) : NumberProblem
}

/** A typed number: the payload to send, or why not. */
sealed interface NumberInput {
    data class Ok(val payload: String) : NumberInput

    data class Bad(val problem: NumberProblem) : NumberInput
}

/**
 * [text] typed in a numeric input of a control with [meta]: rounded to
 * `precision` with its digits, checked against `min` and `max`, in
 * decimal without an exponent.
 */
fun numberInput(text: String, meta: ControlMeta): NumberInput {
    val typed = decimalOf(text.replace(',', '.')) ?: return NumberInput.Bad(NumberProblem.NotANumber)
    val n = meta.precision?.let { roundToPrecision(typed, it) } ?: typed
    meta.min?.let { if (n < it) return NumberInput.Bad(NumberProblem.BelowMin(it)) }
    meta.max?.let { if (n > it) return NumberInput.Bad(NumberProblem.AboveMax(it)) }
    return NumberInput.Ok(n.toPlainString())
}

/** What a numeric input starts with: the value with the digits of `precision`, as received without one. */
fun numberPrefill(value: String?, meta: ControlMeta): String = value?.let { valueDigits(it.trim(), meta.precision) }.orEmpty()

/** Types whose values compare as numbers (enum keys of a non-text type too). */
private val NUMERIC_ECHO = NUMBER_TYPES + setOf("switch", "range")

/**
 * Whether [got] from the status topic is the value [asked] of a control
 * with [meta]: numbers compared as numbers after rounding both to
 * `precision`; `rgb` as three integers, spaces ignored; anything else as
 * exact strings.
 */
fun sameValue(meta: ControlMeta, asked: String, got: String): Boolean {
    if (meta.type == "rgb") return rgbOf(asked)?.let { it == rgbOf(got) } ?: (asked == got)
    if (meta.type in NUMERIC_ECHO || (meta.enum.isNotEmpty() && !isTextType(meta))) {
        val a = numberOf(asked, hex = true)
        val b = numberOf(got, hex = true)
        if (a != null && b != null) {
            val p = meta.precision
            return if (p == null) a.compareTo(b) == 0 else roundToPrecision(a, p).compareTo(roundToPrecision(b, p)) == 0
        }
    }
    return asked == got
}

/** How long a write waits for the status topic after the broker's PUBACK. */
const val ECHO_TIMEOUT_MS = 5_000L

/** How long a short answer ("Accepted by the broker") stays on a control. */
const val SHORT_ANSWER_MS = 3_000L

/**
 * One write in flight to a control's `/on`. [awaitsEcho]: the status topic
 * must bring [asked] (false for `pushbutton`, `wo-switch`, and for a value
 * the control already showed). Times are the wall clock, as the tree's.
 */
data class WbWrite(
    val control: MqttPath,
    val asked: String,
    val awaitsEcho: Boolean,
    val sentAt: Long,
    /** The broker's answer once it came, and when. */
    val answer: PublishAnswer? = null,
    val answeredAt: Long = 0,
)

/** What a control shows of its write. */
sealed interface WriteView {
    /** Muted [asked] with a spinner; not tappable. */
    data class Sending(val asked: String) : WriteView

    /** Ended well: the control shows its status value. */
    data object Done : WriteView

    /** The broker's answer shown for a moment ([PublishAnswer.Accepted] or [PublishAnswer.Unconfirmed] of a write without echo). */
    data class Answered(val answer: PublishAnswer) : WriteView

    /** In the error colours under the title, the control back at its status value, until the next change. */
    data class Failed(val failure: WriteFailure) : WriteView
}

sealed interface WriteFailure {
    data class Refused(val refused: PublishAnswer.Refused) : WriteFailure

    /** No equal value within [ECHO_TIMEOUT_MS] of the PUBACK. */
    data object NoAnswerFromDevice : WriteFailure

    /** No PUBACK within 10 s of sending. */
    data object NoAnswerFromBroker : WriteFailure

    /** Not sent or lost on the way: the publish answer says why. */
    data class NotSent(val failed: PublishAnswer.Failed) : WriteFailure
}

/** Whether the control already shows [asked]: it is sent and ended by the PUBACK, as a driver need not re-publish an unchanged value. */
fun awaitsEcho(meta: ControlMeta, widget: WbWidget, current: String?, asked: String): Boolean = when (widget) {
    WbWidget.PUSHBUTTON, WbWidget.WO_SWITCH -> false
    else -> current == null || !sameValue(meta, asked, current)
}

/**
 * Where [write] of a control with [meta] stands at [now], given the status
 * topic's [history] (newest first): an equal value received since sending
 * ends it, else the broker's answer and the timeouts decide.
 */
fun writeView(write: WbWrite, meta: ControlMeta, history: List<TopicValue>, now: Long): WriteView {
    val answer = write.answer
    if (write.awaitsEcho) {
        val echoed = history.any { v -> v.receivedAt >= write.sentAt && !v.cleared && decodeUtf8(v.payload)?.let { sameValue(meta, write.asked, it) } == true }
        if (echoed) return WriteView.Done
    }
    val view = when (answer) {
        null -> WriteView.Sending(write.asked)
        is PublishAnswer.Refused -> WriteView.Failed(WriteFailure.Refused(answer))
        is PublishAnswer.Failed ->
            WriteView.Failed(if (answer.failure == PublishFailure.TIMEOUT) WriteFailure.NoAnswerFromBroker else WriteFailure.NotSent(answer))
        PublishAnswer.Accepted, PublishAnswer.Unconfirmed -> when {
            !write.awaitsEcho -> if (now - write.answeredAt < SHORT_ANSWER_MS && meta.type in SHORT_ANSWER_TYPES) WriteView.Answered(answer) else WriteView.Done
            now - write.answeredAt < ECHO_TIMEOUT_MS -> WriteView.Sending(write.asked)
            else -> WriteView.Failed(WriteFailure.NoAnswerFromDevice)
        }
    }
    if (view !is WriteView.Failed) return view
    // A failure stays until the next change: a new status value after it ends it.
    val failedAt = if (view.failure == WriteFailure.NoAnswerFromDevice) write.answeredAt + ECHO_TIMEOUT_MS else write.answeredAt
    val changed = history.firstOrNull()?.let { it.receivedAt > failedAt } == true
    return if (changed) WriteView.Done else view
}

/** Types whose write has no status to wait for: their PUBACK is shown for a moment. */
private val SHORT_ANSWER_TYPES = setOf("pushbutton", "wo-switch")
