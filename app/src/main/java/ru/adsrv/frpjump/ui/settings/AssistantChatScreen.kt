package ru.adsrv.frpjump.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.ui.AppTheme

@Composable
fun AssistantChatScreen(vm: AssistantChatViewModel, onBack: () -> Unit, onSetup: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    if (toolsOpen) {
        AssistantToolsRoute(vm.tools, onBack = { toolsOpen = false })
    } else {
        AssistantChatContent(vm.conversation, settings.assistantEnabled, provider, onBack, onSetup, onTools = { toolsOpen = true })
    }
}

/** Synthetic component tests use this same production owner and UI, not an alternative chat model. */
@Composable
internal fun AssistantChatContent(
    conversation: AssistantConversation, enabled: Boolean, provider: AssistantProviderSnapshot,
    onBack: () -> Unit, onSetup: () -> Unit, onTools: (() -> Unit)? = null,
) {
    val state by conversation.state.collectAsStateWithLifecycle()
    val historyScroll = rememberLazyListState()
    LaunchedEffect(state.turns.size, state.pendingText) {
        val count = state.turns.size + if (state.pendingText != null) 1 else 0
        if (count > 0) historyScroll.scrollToItem(count - 1)
    }
    BackHandler { conversation.close(); onBack() }
    val setup = when {
        !enabled -> R.string.chat_disabled
        provider.problem != null -> R.string.assistant_provider_read_failed
        provider.settings?.model.isNullOrBlank() -> R.string.chat_setup
        else -> null
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.ground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        val compact = maxHeight < 400.dp
        Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { conversation.close(); onBack() }, modifier = Modifier.testTag("chat_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
                Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                onTools?.let { action -> TextButton(onClick = action, modifier = Modifier.testTag("chat_tools")) { Text(stringResource(R.string.assistant_tools)) } }
            }
            if (setup != null) {
                Text(stringResource(setup))
                TextButton(onClick = onSetup, modifier = Modifier.testTag("chat_setup")) {
                    Text(stringResource(if (!enabled) R.string.settings else R.string.assistant_provider_title))
                }
            }
            if (state.omitted) Text(stringResource(R.string.chat_omitted), color = AppTheme.colors.muted)
            LazyColumn((if (compact) Modifier.height(160.dp) else Modifier.weight(1f)).fillMaxWidth().testTag("chat_history"), state = historyScroll, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(state.turns) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatUserMessage(turn.user)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.chat_assistant), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
                            SelectionContainer { AssistantAnswerMarkdown(turn.answer) }
                            if (turn.toolFailed) Text(stringResource(R.string.tool_failed), color = AppTheme.colors.muted)
                            ChatSources(turn.sources)
                        }
                    }
                }
                state.pendingText?.let { text -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChatUserMessage(text)
                        Text(stringResource(if (state.stopping) R.string.chat_stopping else when (state.toolProgress) {
                            ChatToolProgress.SEARCH -> R.string.tool_search_progress
                            ChatToolProgress.READ -> R.string.tool_read_progress
                            ChatToolProgress.IMAGE -> R.string.tool_image_progress
                            ChatToolProgress.PROFILE_INFORMATION -> R.string.tool_profile_information_progress
                            ChatToolProgress.SSH_COMMAND -> R.string.tool_ssh_command_progress
                            ChatToolProgress.FAILED -> R.string.tool_failed
                            null -> R.string.chat_waiting
                        }), color = AppTheme.colors.muted)
                        ChatSources(state.currentSources)
                    }
                } }
                if (state.pendingText == null && state.currentSources.isNotEmpty()) item { ChatSources(state.currentSources) }
            }
            if (state.toolFailed) Text(stringResource(R.string.tool_failed), color = AppTheme.colors.muted, modifier = Modifier.testTag("chat_current_tool_failure"))
            state.problem?.let { problem -> Text(stringResource(when (problem) {
                ChatProblem.SETUP -> R.string.chat_setup
                ChatProblem.READ -> R.string.assistant_provider_read_failed
                ChatProblem.KEY -> R.string.chat_key
                ChatProblem.TIMEOUT -> R.string.chat_timeout
                ChatProblem.CONTEXT_FULL -> R.string.chat_context_full
                ChatProblem.INVALID_REPLY -> R.string.chat_invalid_reply
                ChatProblem.PROVIDER -> R.string.chat_provider_failed
                ChatProblem.TOO_LONG -> R.string.chat_too_long
            }), color = AppTheme.colors.error, modifier = Modifier.testTag("chat_error")) }
            if (setup == null && state.problem in setOf(ChatProblem.SETUP, ChatProblem.READ, ChatProblem.KEY, ChatProblem.CONTEXT_FULL)) {
                TextButton(onClick = onSetup) { Text(stringResource(R.string.assistant_provider_title)) }
            }
            OutlinedTextField(value = state.draft, onValueChange = conversation::edit,
                label = { Text(stringResource(R.string.chat_message)) }, enabled = !state.busy,
                minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("chat_input"))
            if (state.busy) OutlinedButton(onClick = conversation::stop, enabled = !state.stopping,
                modifier = Modifier.fillMaxWidth().testTag("chat_stop")) {
                Text(stringResource(if (state.stopping) R.string.chat_stopping else R.string.chat_stop))
            } else Button(onClick = conversation::send, enabled = setup == null && state.draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("chat_send")) { Text(stringResource(R.string.chat_send)) }
        }
    }
}

@Composable
private fun ChatUserMessage(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.chat_you), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.accent)
        SelectionContainer { Text(text, color = AppTheme.colors.accent) }
    }
}

@Composable
private fun ChatSources(sources: List<ChatSource>) {
    if (sources.isNotEmpty()) Column(Modifier.testTag("chat_sources"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.chat_sources), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
        sources.forEach { source -> SelectionContainer { Text("${source.title}\n${source.url}") } }
    }
}
