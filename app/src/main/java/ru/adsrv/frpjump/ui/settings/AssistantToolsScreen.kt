package ru.adsrv.frpjump.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.ui.AppTheme

@Composable
internal fun AssistantToolsEntry(onTools: () -> Unit) {
    SettingRow(tile = { GlyphTile("W") }, label = stringResource(R.string.assistant_tools),
        modifier = Modifier.testTag("assistant_tools_entry"), onClick = onTools) { Chevron() }
}

/** Both entry paths collect the same single committed/pending/error presentation. */
@Composable
internal fun AssistantToolsRoute(binding: AssistantToolsBinding, onBack: () -> Unit) {
    val state by binding.state.collectAsStateWithLifecycle()
    AssistantToolsScreen(state, binding::change, onBack, binding::changeConfirmations)
}

@Composable
internal fun AssistantToolsScreen(state: AssistantToolsUiState, onChange: (AssistantToolId, Boolean) -> Unit, onBack: () -> Unit,
    onConfirmationsChange: (Boolean) -> Unit = {}) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AppTheme.colors.ground).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            IconButton(onClick = onBack, modifier = Modifier.testTag("tools_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.assistant_tools), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(R.string.assistant_tools_notice))
        Group(null) {
            Column(Modifier.testTag("tool_confirmations")) {
                SwitchRow(tile = { GlyphTile("?") }, label = stringResource(R.string.confirmations_setting), hint = stringResource(R.string.confirmations_setting_hint),
                    checked = state.confirmations, busy = CONFIRMATIONS_KEY in state.pending, onChange = onConfirmationsChange)
                if (CONFIRMATIONS_KEY in state.failed) Text(stringResource(R.string.assistant_tool_save_failed), color = AppTheme.colors.error)
            }
        }
        Group(null) {
            AssistantToolId.entries.forEachIndexed { index, id ->
                if (index > 0) RowDivider()
                ToolSwitch(id, state.committed[id], state, onChange)
            }
        }
    }
}

@Composable
private fun ToolSwitch(id: AssistantToolId, checked: Boolean, state: AssistantToolsUiState, onChange: (AssistantToolId, Boolean) -> Unit) {
    Column(Modifier.testTag("tool_${id.wire}")) {
        SwitchRow(tile = { GlyphTile("W") }, label = stringResource(id.title()), hint = stringResource(id.hint()), checked = checked,
            busy = id.wire in state.pending, onChange = { onChange(id, it) })
        if (id.wire in state.failed) Text(stringResource(R.string.assistant_tool_save_failed), color = AppTheme.colors.error)
    }
}
