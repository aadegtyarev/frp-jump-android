package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.chat.ChatToolReceipt
import ru.adsrv.frpjump.ui.AppTheme

internal data class WorkStep(val tool: AssistantToolId, val outcome: ToolOutcome, val count: Int = 1)

/** Old results do not record execution: only success proves the call ran. */
internal fun workSteps(receipts: List<ChatToolReceipt>): List<WorkStep> {
    val steps = mutableListOf<WorkStep>()
    for (receipt in receipts) {
        val result = receipt.result ?: continue
        val id = AssistantToolId.fromWire(receipt.call.name) ?: continue
        if (receipt.started != true && !(receipt.started == null && result.outcome == ToolOutcome.SUCCESS)) continue
        if (result.outcome == ToolOutcome.DECLINED || result.outcome == ToolOutcome.UNKNOWN_TOOL) continue
        val previous = steps.lastOrNull()
        if (previous != null && previous.tool == id && previous.outcome == result.outcome)
            steps[steps.lastIndex] = previous.copy(count = previous.count + 1)
        else steps += WorkStep(id, result.outcome)
    }
    return steps
}

internal fun AssistantToolId.workProgress(): Int = when (this) {
    AssistantToolId.PROFILE_INFORMATION -> R.string.chat_checking_tunnels
    AssistantToolId.TERMINAL_TABS -> R.string.chat_checking_terminals
    else -> progress()
}

@Composable
private fun WorkStepLine(step: WorkStep) {
    val action = stringResource(when (step.tool) {
        AssistantToolId.PROFILE_INFORMATION -> R.string.chat_checked_tunnels
        AssistantToolId.TERMINAL_TABS -> R.string.chat_checked_terminals
        else -> step.tool.receiptTitle()
    })
    val outcome = stringResource(when (step.outcome) {
        ToolOutcome.SUCCESS -> R.string.chat_work_done
        ToolOutcome.TIMEOUT -> R.string.chat_work_timeout
        else -> R.string.chat_work_failed
    })
    val text = if (step.count == 1) "$action · $outcome" else "$action ×${step.count} · $outcome"
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.testTag("chat_work_step"))
}

@Composable
internal fun ChatWorkSteps(steps: List<WorkStep>, collapsed: Boolean, identity: Any? = null) {
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("chat_work")) {
        if (collapsed) {
            val state = stringResource(if (expanded) R.string.chat_work_expanded else R.string.chat_work_collapsed)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("chat_work_toggle").semantics { stateDescription = state }) {
                Text(stringResource(R.string.chat_work_title))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f))
            }
        }
        if (!collapsed || expanded) steps.forEach { WorkStepLine(it) }
    }
}
