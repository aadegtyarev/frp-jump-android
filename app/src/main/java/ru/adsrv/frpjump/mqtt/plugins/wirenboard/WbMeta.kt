package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * A title as Wiren Board meta gives it: a plain string ([texts] holds one
 * entry with a null key), or texts by language key in the JSON's order.
 */
data class WbTitle(val texts: List<Pair<String?, String>>) {
    /**
     * The text for the phone's [language] (`ru`): a plain string as is;
     * else the key equal to [language], else `en`, else the first key; an
     * empty text counts as absent. Null when none is left.
     */
    fun pick(language: String): String? {
        val present = texts.filter { it.second.isNotEmpty() }
        return present.firstOrNull { it.first == null }?.second
            ?: present.firstOrNull { it.first == language }?.second
            ?: present.firstOrNull { it.first == "en" }?.second
            ?: present.firstOrNull()?.second
    }

    companion object {
        /** A string or an object of strings; anything else is no title. */
        fun of(element: JsonElement?): WbTitle? = when (element) {
            is JsonPrimitive -> if (element.isString) WbTitle(listOf(null to element.content)) else null
            is JsonObject -> WbTitle(element.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } })
            else -> null
        }
    }
}

/** One entry of a control's `enum`: the value [key] as written in the meta, and its title. */
data class WbEnumEntry(val key: String, val title: WbTitle)

/**
 * A control's meta, merged from the JSON `<control>/meta` and the one-field
 * topics `meta/<field>` (the JSON wins per field). Numbers are decimal,
 * never binary floating point.
 */
data class ControlMeta(
    val type: String? = null,
    val units: String? = null,
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val precision: BigDecimal? = null,
    val order: BigDecimal? = null,
    val readonly: Boolean = false,
    val hidden: Boolean = false,
    val title: WbTitle? = null,
    val enum: List<WbEnumEntry> = emptyList(),
)

/** The one-field meta topics read, under `<control>/meta/`. */
val ONE_FIELD_META = setOf("type", "units", "min", "max", "precision", "order", "readonly")

/** JSON meta read at most; a larger one is no meta. */
private const val MAX_META_CHARS = 64 * 1024

/** [text] as a JSON object, or null: empty, too large, unparsable, not an object or an empty one. */
internal fun metaObject(text: String?): JsonObject? {
    if (text.isNullOrBlank() || text.length > MAX_META_CHARS) return null
    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    return (parsed as? JsonObject)?.takeIf { it.isNotEmpty() }
}

/** A number as text or a JSON number; null for anything else (`NaN`, words, booleans). */
internal fun decimalOf(text: String?): BigDecimal? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty() || t.length > 64) return null
    return runCatching { BigDecimal(t) }.getOrNull()
}

private fun JsonElement?.primitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

private fun JsonElement?.text(): String? = primitive()?.content

/** `true`, `1` (number or text) or `"true"` is true; `false`, `0` and `"false"` false; anything else not said. */
internal fun flagOf(text: String?): Boolean? = when (text?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

/**
 * A control's meta from its JSON [json] (`<control>/meta`, null when not
 * seen) and its one-field topics [fields] (`type` → its payload); null when
 * it has none: no non-empty JSON object and no one-field topic with text.
 * Empty texts stand for cleared topics and count as absent.
 */
fun parseControlMeta(json: String?, fields: Map<String, String>): ControlMeta? {
    val obj = metaObject(json)
    val one = fields.filter { (k, v) -> k in ONE_FIELD_META && v.isNotEmpty() }
    if (obj == null && one.isEmpty()) return null
    fun field(name: String): String? = obj?.get(name).text() ?: one[name]
    // A field the JSON has but cannot be read is filled from its topic, as a missing one.
    fun number(name: String): BigDecimal? = decimalOf(obj?.get(name).text()) ?: decimalOf(one[name])
    fun flag(name: String): Boolean? = flagOf(obj?.get(name).text()) ?: flagOf(one[name])
    val enumObject = obj?.get("enum") as? JsonObject
    return ControlMeta(
        type = field("type")?.trim()?.takeIf { it.isNotEmpty() },
        units = field("units")?.takeIf { it.isNotBlank() },
        min = number("min"),
        max = number("max"),
        precision = number("precision")?.takeIf { it.signum() > 0 },
        order = number("order"),
        readonly = flag("readonly") ?: false,
        hidden = flagOf(obj?.get("hidden").text()) ?: false,
        title = WbTitle.of(obj?.get("title")),
        enum = enumObject?.mapNotNull { (k, v) -> WbTitle.of(v)?.let { WbEnumEntry(k, it) } }.orEmpty(),
    )
}

/** A device's title from its JSON meta (`/devices/<d>/meta`); null without one. */
fun parseDeviceTitle(json: String?): WbTitle? = WbTitle.of(metaObject(json)?.get("title"))

/** What a letter of `meta/error` says. */
sealed interface WbError {
    /** `r`: the value shown is the last good one. */
    data object Read : WbError

    /** `w`. */
    data object Write : WbError

    /** `p`: the driver cannot poll as often as asked. */
    data object PolledLate : WbError

    /** A letter the convention does not name, shown as is. */
    data class Other(val letter: String) : WbError
}

/** The errors an `meta/error` [text] names, one per letter in order, each once; spaces and commas between them are skipped. */
fun parseErrors(text: String?): List<WbError> {
    if (text.isNullOrEmpty()) return emptyList()
    val out = LinkedHashSet<WbError>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.isWhitespace(cp) || cp == ','.code) continue
        out += when (cp) {
            'r'.code -> WbError.Read
            'w'.code -> WbError.Write
            'p'.code -> WbError.PolledLate
            else -> WbError.Other(String(Character.toChars(cp)))
        }
    }
    return out.toList()
}
