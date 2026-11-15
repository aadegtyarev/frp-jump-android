package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.assistant.AssistantRequestTool
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.chat.*
import ru.adsrv.frpjump.ssh.isolateName
import ru.adsrv.frpjump.ui.AppTheme

/**
 * The turn's tool calls as one quiet block (Р61): while the turn runs, one line per call as it happens (the current one with a small
 * progress mark); once it is finished, one summary line ("Profile information ×2 · SSH command ×3") that opens the grouped list with each
 * call's result. Profile-information results show as their received facts. No accent colour and no plate: the answer is what stands out.
 * [openFacts] starts the finished block opened on the groups that hold received facts (a failed attempt keeps them in view).
 */
@Composable
internal fun ChatToolReceipts(receipts: List<ChatToolReceipt>, information: List<ChatProfileInformation> = emptyList(),
    running: Boolean = false, animate: Boolean = true, openFacts: Boolean = false) {
    if (receipts.isEmpty()) return
    if (running) ChatRunningCalls(receipts, information, animate) else ChatCallsSummary(receipts, information, openFacts && information.isNotEmpty())
}

/** The title of a receipt group: a catalog tool's, the service tool's "Request to turn on a tool", else the cleaned wire name. */
@Composable
private fun receiptTitle(tool: String): String = AssistantToolId.fromWire(tool)?.let { stringResource(it.receiptTitle()) }
    ?: if (tool == AssistantRequestTool.WIRE) stringResource(R.string.tool_request_title) else receiptText(tool, RECEIPT_DETAIL_CHARS)

/**
 * What a call asked for, in one short line (each part cut short): the model's own text for most tools; for profile information its query,
 * profile (by the name the received facts hold, else the first characters of its id) and page, "all" when it asked for everything; for SSH
 * the target's name where the facts give it, then the command; for a request to turn a tool on, that tool's title. UI text only.
 */
@Composable
private fun rememberDetail(information: List<ChatProfileInformation>, receipts: List<ChatToolReceipt>): (ToolCall) -> String? {
    val resources = LocalContext.current.resources
    val all = stringResource(R.string.chat_detail_all)
    val facts = remember(information) { ReceivedNames.of(information) }
    val tabs = remember(receipts) { tabLabels(receipts) }
    return remember(resources, all, facts, tabs) {
        val words = ReceiptWords(all, { resources.getString(R.string.chat_detail_from, it) },
            toolTitle = { wire -> AssistantToolId.fromWire(wire)?.let { resources.getString(it.title()) } },
            profileName = facts.profiles::get, connectionName = { profile, connection -> facts.connections[profile to connection] },
            tabLabel = tabs::get, tabNamed = { profile, user, device, number -> resources.getString(R.string.chat_detail_tab_named, profile, user, device, number) },
            tabPlain = { resources.getString(R.string.chat_detail_tab, it) },
            tabNamedAs = { name, profile, user, device, number -> resources.getString(R.string.chat_detail_tab_name_named, name, profile, user, device, number) },
            tabRenamed = { name, user, device, number -> resources.getString(R.string.chat_detail_tab_name, name, user, device, number) })
        val detail: (ToolCall) -> String? = { call -> receiptDetail(call, words) }
        detail
    }
}

/** The names of profiles and connections this turn's received facts hold; ids the facts do not mention have no name here. */
private class ReceivedNames(val profiles: Map<String, String>, val connections: Map<Pair<String, String>, String>) {
    companion object {
        fun of(information: List<ChatProfileInformation>): ReceivedNames {
            val profiles = HashMap<String, String>(); val connections = HashMap<Pair<String, String>, String>()
            fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            information.forEach { info -> (info.page?.get("results") as? JsonArray)?.forEach { record ->
                val rec = record as? JsonObject ?: return@forEach
                val profile = rec["profile"] as? JsonObject
                val connection = rec["connection"] as? JsonObject
                val pid = profile?.text("id") ?: return@forEach
                profile.text("name")?.let { profiles[pid] = it }
                val cid = connection?.text("id") ?: return@forEach
                (connection.text("name") ?: connection.text("device"))?.let { connections[pid to cid] = it }
            } }
            return ReceivedNames(profiles, connections)
        }
    }
}

/** One line per call, told apart by its parameters; consecutive calls equal in tool and detail share one line ("×2"). The progress mark sits on the line that still has a running call. */
@Composable
private fun ChatRunningCalls(receipts: List<ChatToolReceipt>, information: List<ChatProfileInformation>, animate: Boolean) {
    val detail = rememberDetail(information, receipts)
    Column(Modifier.fillMaxWidth().testTag("chat_tool_calls_running"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groupReceipts(receipts, sameDetail = true).forEachIndexed { index, group ->
            val current = group.items.any { it.result == null }
            val title = receiptTitle(group.tool)
            val count = group.items.size
            val line = if (count > 1) stringResource(R.string.chat_receipt_group, title, count)
                else detail(group.items.first().call)?.let { "$title · $it" } ?: title
            val spoken = if (count > 1) pluralStringResource(R.plurals.chat_receipt_group_description, count, title, count) else line
            Row(Modifier.fillMaxWidth().testTag("chat_tool_call_$index").semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current && animate) QuietProgress() else QuietDot()
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (current) AppTheme.colors.muted else AppTheme.colors.faint)
            }
        }
    }
}

/** The quiet status line of a running turn (waiting, stopping, summary): small and grey, with the progress mark unless it waits for the owner. */
@Composable
internal fun ChatRunStatus(text: Int, spinning: Boolean) {
    Row(Modifier.fillMaxWidth().testTag("chat_run_status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (spinning) QuietProgress() else QuietDot()
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
    }
}

/** The small progress mark; the system's "remove animations" setting stops it (Compose animations follow the animator duration scale). */
@Composable
private fun QuietProgress() = CircularProgressIndicator(Modifier.size(10.dp), color = AppTheme.colors.muted, strokeWidth = 1.5.dp)

@Composable
private fun QuietDot() = Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
    Box(Modifier.size(4.dp).background(AppTheme.colors.faint, CircleShape))
}

@Composable
private fun ChatCallsSummary(receipts: List<ChatToolReceipt>, information: List<ChatProfileInformation>, openFacts: Boolean) {
    var expanded by remember(openFacts) { mutableStateOf(openFacts) }
    val groups = groupReceipts(receipts)
    val detail = rememberDetail(information, receipts)
    val renames = remember(receipts) { tabRenames(receipts) }
    val titles = groups.map { receiptTitle(it.tool) }
    val summary = groups.mapIndexed { i, group -> if (group.items.size > 1) stringResource(R.string.chat_receipt_group, titles[i], group.items.size) else titles[i] }.joinToString(" · ")
    val spoken = stringResource(R.string.chat_calls_description, groups.mapIndexed { i, group ->
        pluralStringResource(R.plurals.chat_receipt_group_description, group.items.size, titles[i], group.items.size) }.joinToString("; "))
    val state = stringResource(if (expanded) R.string.chat_calls_expanded else R.string.chat_calls_collapsed)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_tool_receipts")
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { contentDescription = spoken; stateDescription = state },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.faint, modifier = Modifier.weight(1f, fill = false))
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(14.dp), tint = AppTheme.colors.faint)
    }
    if (expanded) Column(Modifier.testTag("chat_tool_calls_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEachIndexed { index, group ->
            val title = titles[index]
            if (group.items.size == 1) {
                val receipt = group.items.single()
                Column(Modifier.testTag("chat_tool_receipt_$index").then(mergedUnlessFacts(receipt, information))) {
                    Text(detail(receipt.call)?.let { "$title: $it" } ?: title, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.faint)
                    ChatReceiptResult(receipt, information, renames)
                }
            } else if (groups.size == 1) {
                // The summary line already named the only group: its calls show at once, without a second header to tap.
                GroupItems(group, index, title, information, detail, renames)
            } else {
                val holdsFacts = group.items.any { receipt -> information.any { it.result.callId == receipt.result?.callId } }
                var open by remember(openFacts) { mutableStateOf(openFacts && holdsFacts) }
                val count = group.items.size
                val description = pluralStringResource(R.plurals.chat_receipt_group_description, count, title, count)
                val groupState = stringResource(if (open) R.string.chat_calls_expanded else R.string.chat_calls_collapsed)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_tool_group_$index").clickable(role = Role.Button) { open = !open }
                    .semantics(mergeDescendants = true) { contentDescription = description; stateDescription = groupState },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chat_receipt_group, title, count), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.faint)
                }
                if (open) GroupItems(group, index, title, information, detail, renames)
            }
        }
    }
}

/** A group's calls, each under its own label: what the model asked for, else the tool's title (numbered: never the wire name). */
@Composable
private fun GroupItems(group: ReceiptGroup, index: Int, title: String, information: List<ChatProfileInformation>, detail: (ToolCall) -> String?, renames: Map<String, TabRename>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.items.forEachIndexed { n, receipt ->
            Column(Modifier.testTag("chat_tool_group_${index}_item_$n").then(mergedUnlessFacts(receipt, information))) {
                Text(detail(receipt.call) ?: stringResource(R.string.chat_receipt_item, title, n + 1), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.faint)
                ChatReceiptResult(receipt, information, renames)
            }
        }
    }
}

private fun factsOf(receipt: ChatToolReceipt, information: List<ChatProfileInformation>): ChatProfileInformation? =
    receipt.result?.let { result -> information.firstOrNull { it.result.callId == result.callId } }

/** An entry reads as one item, except where it holds the received facts: those stay separate lines, as they were. */
private fun mergedUnlessFacts(receipt: ChatToolReceipt, information: List<ChatProfileInformation>): Modifier =
    if (factsOf(receipt, information) != null) Modifier else Modifier.semantics(mergeDescendants = true) {}

/** One call's result: the received facts for a profile-information call that produced them, else the call and its raw result. */
@Composable
private fun ChatReceiptResult(receipt: ChatToolReceipt, information: List<ChatProfileInformation>, renames: Map<String, TabRename>) {
    val facts = factsOf(receipt, information)
    if (facts != null) ChatProfileInformationFacts(facts) else ChatToolReceiptBody(receipt, renames[receipt.call.id])
}

@Composable
private fun ChatToolReceiptBody(receipt: ChatToolReceipt, rename: TabRename? = null) {
    val result = receipt.result
    val outcome = when (result?.outcome) {
        null, ToolOutcome.SUCCESS -> null
        ToolOutcome.DECLINED -> stringResource(if (result.isNoAnswer()) R.string.chat_receipt_no_answer else R.string.chat_receipt_declined)
        ToolOutcome.FAILED -> stringResource(R.string.chat_receipt_failed)
        ToolOutcome.TIMEOUT -> stringResource(R.string.chat_receipt_timeout)
        ToolOutcome.UNKNOWN_TOOL -> stringResource(R.string.chat_receipt_unknown_tool)
    }
    val resources = LocalContext.current.resources
    // "before → after" of a rename, each as the name (quoted) or the automatic user@device #n: the tab's user and device are always in it.
    val renameLine = rename?.let { r ->
        val label = r.label
        fun shown(name: String?) = name?.let { resources.getString(R.string.chat_detail_name_quoted, isolateName(receiptText(it, RECEIPT_DETAIL_CHARS))) }
            ?: resources.getString(R.string.chat_detail_name_auto)
        resources.getString(R.string.chat_receipt_rename, receiptText(label.profile, RECEIPT_DETAIL_CHARS), receiptText(label.user, RECEIPT_DETAIL_CHARS),
            receiptText(label.device, RECEIPT_DETAIL_CHARS), label.number, shown(r.before), shown(r.after))
    }
    // A rename whose tab no `terminal_tabs` result of the turn names: the tab's id alone. Its reply holds the name, which must not stand alone.
    if (rename == null && receipt.call.name == AssistantToolId.TERMINAL_RENAME.wire && result?.outcome == ToolOutcome.SUCCESS) {
        val id = (receipt.call.arguments["tab_id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        SelectionContainer { Text(resources.getString(R.string.chat_detail_tab, id ?: 0), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted) }
        return
    }
    val body = receiptBody(receipt.call, outcome, result?.text.orEmpty(), keysLine = { keys -> resources.getString(R.string.chat_receipt_keys, keys.joinToString(", ")) }, renameLine = renameLine)
    if (body.isEmpty()) return
    SelectionContainer {
        Text(body, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
    }
}
