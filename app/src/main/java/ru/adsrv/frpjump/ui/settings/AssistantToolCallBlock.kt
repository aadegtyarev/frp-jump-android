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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.assistant.AssistantRequestTool
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.chat.*
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
    if (running) ChatRunningCalls(receipts, animate) else ChatCallsSummary(receipts, information, openFacts && information.isNotEmpty())
}

/** The title of a receipt group: a catalog tool's, the service tool's "Request to turn on a tool", else the cleaned wire name. */
@Composable
private fun receiptTitle(tool: String): String = AssistantToolId.fromWire(tool)?.let { stringResource(it.title()) }
    ?: if (tool == AssistantRequestTool.WIRE) stringResource(R.string.tool_request_title) else receiptText(tool, RECEIPT_DETAIL_CHARS)

@Composable
private fun ChatRunningCalls(receipts: List<ChatToolReceipt>, animate: Boolean) {
    Column(Modifier.fillMaxWidth().testTag("chat_tool_calls_running"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        receipts.forEachIndexed { index, receipt ->
            val current = receipt.result == null
            val title = receiptTitle(receipt.call.name)
            val line = receiptDetail(receipt.call)?.let { "$title · $it" } ?: title
            Row(Modifier.fillMaxWidth().testTag("chat_tool_call_$index").semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Text(receiptDetail(receipt.call)?.let { "$title: $it" } ?: title, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.faint)
                    ChatReceiptResult(receipt, information)
                }
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
                if (open) group.items.forEachIndexed { n, receipt ->
                    Column(Modifier.testTag("chat_tool_group_${index}_item_$n").then(mergedUnlessFacts(receipt, information))) {
                        Text(receiptDetail(receipt.call) ?: group.tool, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.faint)
                        ChatReceiptResult(receipt, information)
                    }
                }
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
private fun ChatReceiptResult(receipt: ChatToolReceipt, information: List<ChatProfileInformation>) {
    val facts = factsOf(receipt, information)
    if (facts != null) ChatProfileInformationFacts(facts) else ChatToolReceiptBody(receipt)
}

@Composable
private fun ChatToolReceiptBody(receipt: ChatToolReceipt) {
    val outcome = receipt.result?.outcome
    SelectionContainer {
        Text("${receipt.call.name}\n${receipt.call.arguments}\n${if (outcome == ToolOutcome.DECLINED) stringResource(if (receipt.result.isNoAnswer()) R.string.chat_receipt_no_answer else R.string.chat_receipt_declined) else outcome ?: "?"}\n${receipt.result?.text.orEmpty()}",
            style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
    }
}
