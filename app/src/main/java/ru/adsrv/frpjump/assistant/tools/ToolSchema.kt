package ru.adsrv.frpjump.assistant.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds a closed object schema. Key order is fixed: type, additionalProperties, properties, required (omitted when empty). */
internal fun objectSchema(build: ToolSchemaBuilder.() -> Unit): JsonObject {
    val builder = ToolSchemaBuilder().apply(build)
    return buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", JsonObject(builder.properties))
        if (builder.required.isNotEmpty()) put("required", buildJsonArray { builder.required.forEach { add(JsonPrimitive(it)) } })
    }
}

internal class ToolSchemaBuilder {
    internal val properties = LinkedHashMap<String, JsonObject>()
    internal val required = ArrayList<String>()

    fun string(name: String, maxLength: Int? = null, required: Boolean = true, minLength: Int? = null) =
        add(name, required) { put("type", "string"); if (minLength != null) put("minLength", minLength); if (maxLength != null) put("maxLength", maxLength) }
    /** A list of strings, each one of [values], with [minItems]..[maxItems] items. */
    fun enumArray(name: String, values: List<String>, minItems: Int, maxItems: Int, required: Boolean = true) =
        add(name, required) {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) })
            put("minItems", minItems); put("maxItems", maxItems)
        }
    /** A string limited to [values] (a closed list the call must pick from). */
    fun enum(name: String, values: List<String>, required: Boolean = true) =
        add(name, required) { put("type", "string"); put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
    fun integer(name: String, minimum: Int? = null, required: Boolean = true, maximum: Int? = null) =
        add(name, required) { put("type", "integer"); if (minimum != null) put("minimum", minimum); if (maximum != null) put("maximum", maximum) }

    private fun add(name: String, required: Boolean, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        properties[name] = buildJsonObject(body)
        if (required) this.required += name
    }
}
