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

/** The actual bounded received facts, not a second generated answer. Parsed off Main by Chat. */
@Composable
internal fun ChatProfileInformationResults(results: List<ChatProfileInformation>, show: Boolean = false) {
    results.forEach { result ->
        var expanded by remember(result.result.callId) { mutableStateOf(false) }
        Column(Modifier.testTag("chat_profile_information"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("chat_profile_information_toggle")) {
                Text(stringResource(R.string.profile_information_received))
            }
            if (expanded || show) SelectionContainer {
                Column(Modifier.testTag("chat_profile_information_facts"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val page = result.page
                    if (page == null) Text(result.result.text)
                    else {
                        val records = page["results"] as? JsonArray ?: JsonArray(emptyList())
                        Text(stringResource(R.string.profile_information_count, records.size, page.field("total_records").orEmpty()))
                        val selection = listOfNotNull(page.field("query")?.takeIf { it.isNotBlank() }, page.field("profile_id")).joinToString(" · ")
                        if (selection.isNotEmpty()) Text(stringResource(R.string.profile_information_query, selection))
                        if (page.obj("source_status")?.get("complete") != JsonPrimitive(true))
                            Text(stringResource(R.string.profile_information_partial), color = AppTheme.colors.muted)
                        if (records.isEmpty()) Text(stringResource(R.string.profile_information_no_known_matches))
                        records.forEach { value -> (value as? JsonObject)?.let { record ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val profile = record.obj("profile")
                                val device = record.obj("device")
                                val connection = record.obj("connection")
                                if (profile == null) Text(stringResource(R.string.profile_information_record_unavailable))
                                else {
                                    Text(listOfNotNull(profile.field("name"), device?.field("name") ?: connection?.field("device"), device?.field("label")).joinToString(" · "))
                                    if (connection == null) Text(profile.field("id").orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    val state = when {
                                        profile["available"] == JsonPrimitive(false) -> R.string.profile_information_unavailable
                                        device == null -> R.string.profile_information_unknown
                                        device["disabled"] == JsonPrimitive(true) -> R.string.profile_information_disabled
                                        device["checking"] == JsonPrimitive(true) || device["stale"] == JsonPrimitive(true) ||
                                            profile.obj("device_list")?.get("stale") == JsonPrimitive(true) -> R.string.profile_information_stale
                                        device["online"] == JsonPrimitive(true) -> R.string.profile_information_online
                                        else -> R.string.profile_information_offline
                                    }
                                    Text(stringResource(state), color = AppTheme.colors.muted)
                                    record.obj("operation")?.let { operation ->
                                        Text(listOfNotNull(operation.field("key"), operation.field("pending")).joinToString(" · "))
                                        operation.field("error")?.let { Text(stringResource(R.string.profile_information_operation_failed), color = AppTheme.colors.muted) }
                                    }
                                    connection?.let { c ->
                                        Text(listOfNotNull(c.field("name"), c.field("kind"), c.field("port"), c.field("user")).joinToString(" · "))
                                        Text("${profile.field("id")} / ${c.field("id")}", style = MaterialTheme.typography.bodySmall)
                                        c.obj("tunnel")?.field("status")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                                if ((record["shortened_fields"] as? JsonArray)?.isNotEmpty() == true)
                                    Text(stringResource(R.string.profile_information_shortened), color = AppTheme.colors.muted)
                            }
                        } }
                        page.field("next_offset")?.let { Text(stringResource(R.string.profile_information_more, it)) }
                    }
                }
            }
        }
    }
}
private fun JsonObject.field(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
