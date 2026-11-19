package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.settings.WindowSplit
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.MessageSquareGlyph
import ru.adsrv.frpjump.ui.ServerGlyph

/**
 * Settings › Assistant (R39): the master switch, then the setup (provider, tools, own instructions, limits) and the chat (text size,
 * open the chat). Every row is shown whether the assistant is on or not, as before it moved here from the Settings list. Back returns
 * to Settings; the rows open their screens over this one.
 */
@Composable
fun AssistantSettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onChat: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val provider by vm.assistantProvider.collectAsStateWithLifecycle()
    val tools by vm.tools.state.collectAsStateWithLifecycle()
    var toolsSetup by rememberSaveable { mutableStateOf(false) }
    var providerSetup by rememberSaveable { mutableStateOf(false) }
    var ownerSetup by rememberSaveable { mutableStateOf(false) }
    var limitsSetup by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    if (toolsSetup) {
        AssistantToolsRoute(vm.tools, onBack = { toolsSetup = false })
        return
    }
    if (limitsSetup) {
        AssistantLimitsScreen(settings.assistantLimits, onChange = vm::setAssistantLimits, onBack = { limitsSetup = false })
        return
    }
    if (ownerSetup) {
        AssistantOwnerInstructionsScreen(settings.assistantOwnerInstructions, onSave = vm::setAssistantOwnerInstructions, onBack = { ownerSetup = false })
        return
    }
    if (providerSetup) {
        AssistantProviderScreen(provider, settings.assistantLimits, vm::setAssistantLimits, onSave = { draft, done -> vm.saveAssistantProvider(draft, done); Unit },
            onModels = vm::assistantModels, onReload = { vm.reloadAssistantProvider() }, onBack = { providerSetup = false })
        return
    }
    val decimal = java.text.DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0]).decimalSeparator
    val toolsOn = AssistantToolId.switches.count { tools.committed.switch(it) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("assistant_settings_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.assistant_title), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Group(null) {
            AssistantModuleSetting(settings.assistantEnabled, vm::setAssistantEnabled)
        }
        Group(stringResource(R.string.settings_assistant_setup)) {
            val current = provider.settings
            SettingRow(
                tile = { IconTile(ServerGlyph) },
                label = stringResource(R.string.assistant_provider_title),
                hint = when {
                    provider.problem != null -> stringResource(R.string.assistant_provider_read_failed)
                    current != null && current.model.isNotBlank() ->
                        stringResource(R.string.assistant_provider_row, current.model, WindowSplit.compactTokens(current.contextTokens, decimal))
                    else -> stringResource(R.string.assistant_provider_not_selected)
                },
                modifier = Modifier.testTag("assistant_provider_entry"),
                onClick = { providerSetup = true },
            ) { Chevron() }
            RowDivider()
            AssistantToolsEntry(stringResource(R.string.assistant_tools_row_hint, toolsOn, AssistantToolId.switches.size)) { toolsSetup = true }
            RowDivider()
            AssistantOwnerInstructionsEntry(settings.assistantOwnerInstructions) { ownerSetup = true }
            RowDivider()
            AssistantLimitsEntry { limitsSetup = true }
        }
        Group(stringResource(R.string.chat_title)) {
            AssistantChatTextSizeSetting(settings.assistantChatTextSize, vm::setAssistantChatTextSize)
            RowDivider()
            AssistantChatEntry(onChat)
        }
    }
}

/** The "Open chat" row of Settings › Assistant; the isolated navigation component check shows it too. */
@Composable
internal fun AssistantChatEntry(onChat: () -> Unit) {
    SettingRow(tile = { IconTile(MessageSquareGlyph) }, label = stringResource(R.string.assistant_open_chat),
        modifier = Modifier.testTag("assistant_chat_entry"), onClick = onChat) { Chevron() }
}
