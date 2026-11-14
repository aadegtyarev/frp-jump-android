package ru.adsrv.frpjump.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The chat's own text size, a multiplier (in percent) on top of the system font size. A display preference only: it is stored
 * as `assistant_chat_text_size`, omitted at the default, and never read for a prompt, a permission or a confirmation.
 */
object ChatTextSize {
    val STEPS = listOf(90, 100, 115, 130, 150, 175, 200)
    const val DEFAULT = 100

    fun isStep(percent: Int) = percent in STEPS

    /** The multiplier for [percent] (a step; anything else is snapped first). */
    fun factor(percent: Int): Float = snap(percent.toDouble()) / 100f

    /** The nearest step; halfway between two steps takes the smaller one. NaN and infinities read as the default. */
    fun snap(value: Double): Int =
        if (!value.isFinite()) DEFAULT else STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT

    /** A stored value: a JSON number snaps to the nearest step; a string, boolean, null, object or array reads as the default. */
    fun normalize(raw: JsonElement?): Int {
        val number = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull() ?: return DEFAULT
        return snap(number)
    }
}
