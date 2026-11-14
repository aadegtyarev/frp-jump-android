package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.assistant.chat.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme

/**
 * The actual bounded received facts, not a second generated answer. Parsed off Main by Chat. Shown inside the turn's tool-call block, so
 * as quiet as the other tool output (Р62): small and muted, the secondary lines fainter; the answer is what stands out.
 */
@Composable
internal fun ChatProfileInformationFacts(result: ChatProfileInformation) {
    val style = MaterialTheme.typography.bodySmall
    val muted = AppTheme.colors.muted
    val faint = AppTheme.colors.faint
    SelectionContainer {
        Column(Modifier.testTag("chat_profile_information_facts"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val page = result.page
            if (page == null) Text(result.result.text, style = style, color = muted)
            else {
                val records = page["results"] as? JsonArray ?: JsonArray(emptyList())
                Text(stringResource(R.string.profile_information_count, records.size, page.field("total_records").orEmpty()), style = style, color = muted)
                val selection = listOfNotNull(page.field("query")?.takeIf { it.isNotBlank() }, page.field("profile_id")).joinToString(" · ")
                if (selection.isNotEmpty()) Text(stringResource(R.string.profile_information_query, selection), style = style, color = muted)
                if (page.obj("source_status")?.get("complete") != JsonPrimitive(true))
                    Text(stringResource(R.string.profile_information_partial), style = style, color = faint)
                if (records.isEmpty()) Text(stringResource(R.string.profile_information_no_known_matches), style = style, color = muted)
                records.forEach { value -> (value as? JsonObject)?.let { record ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val profile = record.obj("profile")
                        val device = record.obj("device")
                        val connection = record.obj("connection")
                        if (profile == null) Text(stringResource(R.string.profile_information_record_unavailable), style = style, color = muted)
                        else {
                            Text(listOfNotNull(profile.field("name"), device?.field("name") ?: connection?.field("device"), device?.field("label")).joinToString(" · "), style = style, color = muted)
                            // The long profile id only on the record that is the profile itself; a device or connection record repeats it for nothing.
                            if (device == null && connection == null) Text(profile.field("id").orEmpty(), style = style, color = faint)
                            val state = when {
                                profile["available"] == JsonPrimitive(false) -> R.string.profile_information_unavailable
                                device == null -> R.string.profile_information_unknown
                                device["disabled"] == JsonPrimitive(true) -> R.string.profile_information_disabled
                                device["checking"] == JsonPrimitive(true) || device["stale"] == JsonPrimitive(true) ||
                                    profile.obj("device_list")?.get("stale") == JsonPrimitive(true) -> R.string.profile_information_stale
                                device["online"] == JsonPrimitive(true) -> R.string.profile_information_online
                                else -> R.string.profile_information_offline
                            }
                            Text(stringResource(state), style = style, color = faint)
                            record.obj("operation")?.let { operation ->
                                Text(listOfNotNull(operation.field("key"), operation.field("pending")).joinToString(" · "), style = style, color = muted)
                                operation.takeIf(::operationFailed)?.let { Text(stringResource(R.string.profile_information_operation_failed), style = style, color = faint) }
                            }
                            connection?.let { c ->
                                Text(listOfNotNull(c.field("name"), c.field("kind"), c.field("port"), c.field("user")).joinToString(" · "), style = style, color = muted)
                                Text("${profile.field("id")} / ${c.field("id")}", style = style, color = faint)
                                c.obj("tunnel")?.field("status")?.let { Text(it, style = style, color = faint) }
                            }
                        }
                        if ((record["shortened_fields"] as? JsonArray)?.isNotEmpty() == true)
                            Text(stringResource(R.string.profile_information_shortened), style = style, color = faint)
                    }
                } }
                page.field("next_offset")?.let { Text(stringResource(R.string.profile_information_more, it), style = style, color = muted) }
            }
        }
    }
}
/**
 * An operation record that has an error: the old fixed phrase (a string, in chats saved by 0.3.70 and earlier) or `{"kind": ...}`.
 * Null or missing: none.
 */
internal fun operationFailed(operation: JsonObject): Boolean = operation["error"].let { it != null && it !is JsonNull }

private fun JsonObject.field(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
