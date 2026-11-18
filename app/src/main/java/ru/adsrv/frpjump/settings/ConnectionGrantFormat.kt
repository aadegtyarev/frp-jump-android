package ru.adsrv.frpjump.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.ops.ActionClass

/** At most this many records are read; the rest are ignored. */
const val MAX_CONNECTION_RECORDS = 500

/** Whether a class has a question at all: some tool of it changes something and asks. Only such a class can be remembered. */
internal fun ActionClass.hasQuestion(): Boolean = AssistantToolId.entries.any { it.actionClass == this && it.effect.changes && it.confirms }

/**
 * The strict reader of `assistant_confirmations.overrides` (fail closed; each field on its own; an invalid record never resets `global` or any
 * other setting and never allows anything). A record is kept only if `scope.kind == "target"`, both ids are non-empty strings, `class` is a known
 * class with a question and `grantable`, `mode == "off"`, `fp` is a string starting `v1:`, `tools` is a non-empty array of known tool wire names that
 * are grantable and of that class (anything else in it is dropped; empty after that: the record is ignored) and `at` is a number. Duplicates by
 * (class, profile, connection): the last wins. At most [MAX_CONNECTION_RECORDS] array elements are read. A broken array reads as empty.
 */
internal fun readConnectionRecords(raw: JsonElement?): List<ConfirmationOverride> {
    val array = raw as? JsonArray ?: return emptyList()
    val read = LinkedHashMap<Triple<String, String, String>, ConfirmationOverride>()
    for (element in array.take(MAX_CONNECTION_RECORDS)) {
        val record = readConnectionRecord(element) ?: continue
        val key = Triple(record.actionClass, record.scope.id, record.scope.sub.orEmpty())
        read.remove(key); read[key] = record
    }
    return read.values.toList()
}

private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun readConnectionRecord(element: JsonElement): ConfirmationOverride? {
    val obj = element as? JsonObject ?: return null
    val scope = obj["scope"] as? JsonObject ?: return null
    if (string(scope["kind"]) != "target") return null
    val profile = string(scope["id"])?.takeIf { it.isNotEmpty() } ?: return null
    val connection = string(scope["sub"])?.takeIf { it.isNotEmpty() } ?: return null
    val actionClass = ActionClass.entries.firstOrNull { it.wire == string(obj["class"]) }?.takeIf { it.grantable && it.hasQuestion() } ?: return null
    if (string(obj["mode"]) != "off") return null
    val fp = string(obj["fp"])?.takeIf { it.startsWith("v1:") } ?: return null
    val names = (obj["tools"] as? JsonArray) ?: return null
    val tools = names.mapNotNull { string(it) }.mapNotNull { AssistantToolId.fromWire(it) }.filter { it.grantable && it.actionClass == actionClass }.map { it.wire }.distinct()
    if (tools.isEmpty()) return null
    val at = (obj["at"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
    return ConfirmationOverride(ConfirmationScope("target", profile, connection), actionClass.wire, ConfirmationMode.OFF, fp, tools, at)
}

/**
 * The records after what the owner switched, computed in the same write as the switch: the assistant off removes every record; a tool that is out of
 * effect leaves every record for good (an emptied record is removed), so switching it on again brings nothing back. Also applied to a file just read.
 */
internal fun ConfirmationPolicy.narrowed(after: AppSettings): ConfirmationPolicy {
    if (overrides.isEmpty()) return this
    if (!after.assistantEnabled) return copy(overrides = emptyList())
    val now = after.assistantTools()
    val kept = overrides.mapNotNull { record ->
        val tools = record.tools.filter { wire -> AssistantToolId.fromWire(wire)?.let { now[it] } == true }
        if (tools.isEmpty()) null else if (tools == record.tools) record else record.copy(tools = tools)
    }
    return if (kept == overrides) this else copy(overrides = kept)
}
