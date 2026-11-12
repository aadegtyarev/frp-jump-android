package ru.adsrv.frpjump.assistant.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A string with a surrogate that is not half of a valid pair: it cannot be encoded, and a command line would carry `?` for it. */
internal fun hasLoneSurrogate(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (Character.isHighSurrogate(c)) { if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) i += 2 else return true }
        else if (Character.isLowSurrogate(c)) return true
        else i++
    }
    return false
}

/** Raised by [StrictArgs] for any argument the schema does not allow; carries no argument text. */
internal class InvalidArguments : RuntimeException("Invalid tool arguments", null, false, false)

/**
 * Strict reader of one tool call's arguments and the only place that inspects [JsonPrimitive].
 * Not a decoder: a JSON string is never accepted for a number, and unknown keys are rejected.
 * Lengths are UTF-16 units.
 */
internal class StrictArgs(private val json: JsonObject) {
    /** The call has exactly these keys. */
    fun exactKeys(vararg keys: String) { if (json.keys != keys.toSet()) throw InvalidArguments() }
    /** The call has no keys other than these. */
    fun onlyKeys(vararg keys: String) { if (!keys.toSet().containsAll(json.keys)) throw InvalidArguments() }

    /** A JSON string of at most [max] units; nonblank unless [allowBlank]. */
    fun string(key: String, max: Int = Int.MAX_VALUE, allowBlank: Boolean = false): String {
        val primitive = json[key] as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw InvalidArguments()
        val value = primitive.content
        if (value.length > max || (!allowBlank && value.isBlank())) throw InvalidArguments()
        // What the owner is shown must be what runs: a lone surrogate would be encoded as `?` (a glob in a command line).
        if (hasLoneSurrogate(value)) throw InvalidArguments()
        return value
    }
    /** Like [string], or null when the key is absent. */
    fun optionalString(key: String, max: Int = Int.MAX_VALUE, allowBlank: Boolean = false): String? =
        if (key in json) string(key, max, allowBlank) else null
    /** A non-string JSON number that is an Int at least [min], or null when absent. */
    fun optionalInt(key: String, min: Int = Int.MIN_VALUE): Int? {
        if (key !in json) return null
        val primitive = json[key] as? JsonPrimitive
        if (primitive == null || primitive.isString) throw InvalidArguments()
        val value = primitive.intOrNull ?: throw InvalidArguments()
        if (value < min) throw InvalidArguments()
        return value
    }
}
