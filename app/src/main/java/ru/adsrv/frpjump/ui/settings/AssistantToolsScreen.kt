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
    AssistantToolsScreen(state, binding::change, onBack)
}

@Composable
internal fun AssistantToolsScreen(state: AssistantToolsUiState, onChange: (String, Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AppTheme.colors.ground).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            IconButton(onClick = onBack, modifier = Modifier.testTag("tools_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.assistant_tools), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(R.string.assistant_tools_notice))
        Group(null) {
            ToolSwitch("web_search", R.string.tool_search, R.string.tool_search_hint, state.committed.search, state, onChange)
            RowDivider()
            ToolSwitch("read_page", R.string.tool_read, R.string.tool_read_hint, state.committed.readPage, state, onChange)
            RowDivider()
            ToolSwitch("inspect_image", R.string.tool_image, R.string.tool_image_hint, state.committed.inspectImage, state, onChange)
            RowDivider()
            ToolSwitch("profile_information", R.string.tool_profile_information, R.string.tool_profile_information_hint, state.committed.profileInformation, state, onChange)
        }
    }
}

@Composable
private fun ToolSwitch(id: String, title: Int, hint: Int, checked: Boolean, state: AssistantToolsUiState, onChange: (String, Boolean) -> Unit) {
    Column(Modifier.testTag("tool_$id")) {
        SwitchRow(tile = { GlyphTile("W") }, label = stringResource(title), hint = stringResource(hint), checked = checked,
            busy = id in state.pending, onChange = { onChange(id, it) })
        if (id in state.failed) Text(stringResource(R.string.assistant_tool_save_failed), color = AppTheme.colors.error)
    }
}
