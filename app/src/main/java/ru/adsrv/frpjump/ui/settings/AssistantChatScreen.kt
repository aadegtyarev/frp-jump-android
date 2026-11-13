package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.ui.AppDropdownMenu
import ru.adsrv.frpjump.assistant.chat.*
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.ui.AppTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester

@Composable
fun AssistantChatScreen(vm: AssistantChatViewModel, onBack: () -> Unit, onSetup: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val toolsState by vm.tools.state.collectAsStateWithLifecycle()
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    // A terminal quote (Ask the assistant) lands in the chat itself: the Tools sub-screen, if it was open, gives way to it.
    // Only a quote taken since this screen last looked: coming back to the chat (or recreation) must not close Tools again.
    val quotes by vm.quotesTaken.collectAsStateWithLifecycle()
    var quotesSeen by rememberSaveable { mutableStateOf(quotes) }
    LaunchedEffect(quotes) { if (quotes != quotesSeen) { quotesSeen = quotes; toolsOpen = false } }
    if (toolsOpen) {
        AssistantToolsRoute(vm.tools, onBack = { toolsOpen = false })
    } else {
        AssistantChatContent(vm.conversation, settings.assistantEnabled, provider, onBack, onSetup, onTools = { toolsOpen = true }, onClear = vm::clearHistory,
            confirmationsOff = settings.assistantConfirmations.global == ConfirmationMode.OFF, onConfirmationsOn = vm::turnConfirmationsOn,
            confirmationsOnFailed = CONFIRMATIONS_KEY in toolsState.failed)
    }
}

/** Synthetic component tests use this same production owner and UI, not an alternative chat model. */
@Composable
internal fun AssistantChatContent(
    conversation: AssistantConversation, enabled: Boolean, provider: AssistantProviderSnapshot,
    onBack: () -> Unit, onSetup: () -> Unit, onTools: (() -> Unit)? = null, onClear: (() -> Unit)? = null,
    /** While true the top bar shows the compact "confirmations are off" mark; [onConfirmationsOn] turns requests back on. */
    confirmationsOff: Boolean = false, onConfirmationsOn: (() -> Unit)? = null, confirmationsOnFailed: Boolean = false,
) {
    val state by conversation.state.collectAsStateWithLifecycle()
    val historyScroll = rememberLazyListState()
    LaunchedEffect(state.turns.size, state.pendingText, state.toolFailures.size, state.information.size, state.confirmation?.id) {
        val count = state.turns.size + if (state.pendingText != null || state.toolFailures.isNotEmpty() || state.information.isNotEmpty() || state.currentSources.isNotEmpty()) 1 else 0
        if (count > 0) historyScroll.scrollToItem(count - 1)
    }
    // The field keeps its own cursor; a draft changed from outside (loaded, a quote added, cleared by Send) puts it at the end.
    var field by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    LaunchedEffect(state.draft) {
        val live = conversation.state.value.draft
        if (field.text != live) field = TextFieldValue(live, TextRange(live.length))
    }
    BackHandler { onBack() }
    val setup = when {
        !enabled -> R.string.chat_disabled
        provider.problem != null -> R.string.assistant_provider_read_failed
        provider.settings?.model.isNullOrBlank() -> R.string.chat_setup
        else -> null
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(AppTheme.colors.ground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        val compact = maxHeight < 400.dp
        val noticeMax = if (compact) Dp.Infinity else maxHeight * 0.15f
        val cardInView = remember { BringIntoViewRequester() }
        // Where the "confirmations are off" mark goes is decided by measuring, not by locale or font scale: in the top bar when the whole
        // title still fits next to it, else beside the Send/Stop button (same tap and long press explanation). The widths come from the
        // text measurer, so the decision does not depend on where the mark currently is (no flip-flop).
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val titleText = stringResource(R.string.chat_title)
        val toolsText = stringResource(R.string.assistant_tools)
        val titleStyle = MaterialTheme.typography.titleLarge
        val toolsStyle = MaterialTheme.typography.labelLarge
        val markInTopBar = remember(maxWidth, titleText, toolsText, titleStyle, toolsStyle, density, onTools != null, onClear != null) {
            with(density) {
                fun width(text: String, style: androidx.compose.ui.text.TextStyle) = measurer.measure(androidx.compose.ui.text.AnnotatedString(text), style).size.width.toDp()
                val used = 48.dp + width(titleText, titleStyle) + (if (onTools != null) width(toolsText, toolsStyle) + 24.dp else 0.dp) + (if (onClear != null) 48.dp else 0.dp)
                used + 48.dp + 8.dp <= maxWidth - 32.dp
            }
        }
        val mark: @Composable () -> Unit = { if (confirmationsOff && onConfirmationsOn != null) ConfirmationsOffIndicator(onConfirmationsOn, onTools, confirmationsOnFailed) }
        // When a question appears in the compact (scrolling) layout, show it from its top: tool, target and command first.
        LaunchedEffect(state.confirmation?.id) { if (compact && state.confirmation != null) cardInView.bringIntoView() }
        Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
                Text(titleText, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("chat_title"))
                if (markInTopBar) mark()
                onTools?.let { action -> TextButton(onClick = action, modifier = Modifier.testTag("chat_tools")) { Text(stringResource(R.string.assistant_tools)) } }
                onClear?.let { clear ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !state.busy && !state.loading && !state.restoring) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.chat_history_actions))
                        }
                        AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_history_clear)) }, onClick = { menu = false; clear() })
                        }
                    }
                }
            }
            if (state.loading || state.restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Plain-text notices above and below the history are capped (and scroll) so that a long one at large font cannot squeeze the
            // history away; buttons (setup, retry) stay outside the cap and always visible.
            if (setup != null) {
                Text(stringResource(setup))
                TextButton(onClick = onSetup, modifier = Modifier.testTag("chat_setup")) {
                    Text(stringResource(if (!enabled) R.string.settings else R.string.assistant_provider_title))
                }
            }
            state.historyProblem?.let { problem ->
                Text(stringResource(when (problem) {
                    ChatHistoryProblem.READ -> R.string.chat_history_read_failed
                    ChatHistoryProblem.WRITE -> R.string.chat_history_write_failed
                    ChatHistoryProblem.CLEAR -> R.string.chat_history_clear_failed
                }), color = AppTheme.colors.error)
                TextButton(onClick = conversation::retryHistory, enabled = !state.loading && !state.busy) { Text(stringResource(R.string.chat_history_retry)) }
            }
            if (state.summarized || state.contextChanged || state.omitted) CappedNotices(noticeMax, "chat_notices_above") {
                if (state.summarized) Text(stringResource(R.string.chat_summary_saved), color = AppTheme.colors.muted)
                if (state.contextChanged) Text(stringResource(R.string.chat_context_changed), color = AppTheme.colors.muted)
                if (state.omitted) Text(stringResource(R.string.chat_omitted), color = AppTheme.colors.muted)
            }
            // While a question is open the message field (disabled during a run anyway) is hidden; Stop stays at the bottom.
            val asking = state.asking
            val history: @Composable (Modifier) -> Unit = { modifier -> LazyColumn(modifier.fillMaxWidth().testTag("chat_history"), state = historyScroll, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(state.turns) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatUserMessage(turn.user)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.chat_assistant), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
                            if (turn.unanswered) Text(stringResource(R.string.chat_unanswered), color = AppTheme.colors.muted, modifier = Modifier.testTag("chat_unanswered"))
                            else if (turn.interrupted) Text(stringResource(R.string.chat_interrupted), color = AppTheme.colors.muted)
                            SelectionContainer { AssistantAnswerMarkdown(turn.answer) }
                            if (turn.toolFailures.isNotEmpty()) ChatToolFailures(turn.toolFailures)
                            else if (turn.toolFailed) Text(stringResource(R.string.tool_failed), color = AppTheme.colors.muted)
                            ChatProfileInformationResults(turn.information)
                            ChatSources(turn.sources)
                            ChatToolDeclines(turn.receipts)
                            ChatToolReceipts(turn.receipts)
                        }
                    }
                }
                state.pendingText?.let { text -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChatUserMessage(text)
                        Text(stringResource(if (state.stopping) R.string.chat_stopping else if (state.confirmation != null) R.string.confirm_waiting else when (val progress = state.toolProgress) {
                            is ChatToolProgress.Tool -> progress.id.progress()
                            ChatToolProgress.SUMMARY -> R.string.chat_summary_progress
                            ChatToolProgress.FAILED -> if (state.toolFailures.isEmpty()) R.string.tool_failed else R.string.chat_waiting
                            null -> R.string.chat_waiting
                        }), color = AppTheme.colors.muted)
                        ChatToolFailures(state.toolFailures, Modifier.testTag("chat_current_tool_failure"))
                        ChatProfileInformationResults(state.information, state.problem != null)
                        ChatSources(state.currentSources)
                        ChatToolDeclines(state.receipts)
                        ChatToolReceipts(state.receipts)
                    }
                } }
                if (state.pendingText == null && (state.currentSources.isNotEmpty() || state.toolFailures.isNotEmpty() || state.information.isNotEmpty() || state.receipts.isNotEmpty())) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChatToolFailures(state.toolFailures, Modifier.testTag("chat_current_tool_failure"))
                        ChatProfileInformationResults(state.information, state.problem != null)
                        ChatSources(state.currentSources)
                        ChatToolDeclines(state.receipts)
                        ChatToolReceipts(state.receipts)
                    }
                }
            } }
            // The question sits under the history, outside the list. It and Stop are measured first and the history takes what is left
            // (weight), shrinking to nothing only when the question needs the room. A fresh question gets a fresh card (key), so its scroll
            // state starts at the top.
            val question: @Composable () -> Unit = {
                state.confirmation?.let { pending ->
                    if (!state.stopping) key(pending.id) {
                        Box(Modifier.bringIntoViewRequester(cardInView)) {
                            ChatConfirmationCard(pending, onAnswer = { conversation.answer(pending.id, it) }, wide = compact)
                        }
                    }
                }
            }
            if (compact) { history(Modifier.height(160.dp)); question() }
            else Box(Modifier.weight(1f).fillMaxWidth()) { Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) { history(Modifier.weight(1f)); question() } }
            if (state.confirmationNotSaved || state.problem != null) CappedNotices(noticeMax, "chat_notices_below") {
                // Stays until the next Send, not only while the run is shown: the owner must learn that "Always" was not saved.
                if (state.confirmationNotSaved) Text(stringResource(R.string.confirm_not_saved), color = AppTheme.colors.error, modifier = Modifier.testTag("chat_confirm_not_saved"))
                state.problem?.let { problem -> Text(stringResource(when (problem) {
                    ChatProblem.SETUP -> R.string.chat_setup
                    ChatProblem.READ -> R.string.assistant_provider_read_failed
                    ChatProblem.KEY -> R.string.chat_key
                    ChatProblem.TIMEOUT -> R.string.chat_timeout
                    ChatProblem.CONTEXT_FULL -> R.string.chat_context_full
                    ChatProblem.INVALID_REPLY -> R.string.chat_invalid_reply
                    ChatProblem.PROVIDER -> R.string.chat_provider_failed
                    ChatProblem.TOO_LONG -> R.string.chat_too_long
                    ChatProblem.SUMMARY -> R.string.chat_summary_failed
                    ChatProblem.HISTORY_FULL -> R.string.chat_history_full
                }) + state.problemStatus?.takeIf { state.problem == ChatProblem.SUMMARY }?.let { " (HTTP $it)" }.orEmpty(), color = AppTheme.colors.error, modifier = Modifier.testTag("chat_error")) }
            }
            if (setup == null && state.problem in setOf(ChatProblem.SETUP, ChatProblem.READ, ChatProblem.KEY, ChatProblem.CONTEXT_FULL, ChatProblem.SUMMARY)) {
                TextButton(onClick = onSetup) { Text(stringResource(R.string.assistant_provider_title)) }
            }
            if (!asking) OutlinedTextField(value = field, onValueChange = { value ->
                    if (value.text != field.text) conversation.edit(value.text)
                    // The draft the conversation holds now: a refused edit (too long, a run) shows as it was.
                    val live = conversation.state.value.draft
                    field = if (value.text == live) value else TextFieldValue(live, TextRange(live.length))
                },
                label = { Text(stringResource(R.string.chat_message)) }, enabled = !state.busy && !state.loading && state.historyProblem != ChatHistoryProblem.READ,
                minLines = 1, maxLines = if (density.fontScale > 1.3f) 2 else 3, modifier = Modifier.fillMaxWidth().testTag("chat_input"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!markInTopBar) mark()
                if (state.busy) OutlinedButton(onClick = conversation::stop, enabled = !state.stopping,
                    modifier = Modifier.weight(1f).testTag("chat_stop")) {
                    Text(stringResource(if (state.stopping) R.string.chat_stopping else R.string.chat_stop))
                } else Button(onClick = conversation::send, enabled = setup == null && state.draft.isNotBlank() && !state.loading && !state.restoring && state.historyProblem != ChatHistoryProblem.READ,
                    modifier = Modifier.weight(1f).testTag("chat_send")) { Text(stringResource(R.string.chat_send)) }
            }
        }
    }
}

/**
 * Plain-text notices in a region that never takes more than [max]: the cap is snapped to whole body lines (at least one), so a line is
 * never cut in half, and while more is below, a short mark says so (the same string as the confirmation card's section).
 */
@Composable
private fun CappedNotices(max: Dp, tag: String, content: @Composable ColumnScope.() -> Unit) {
    // No cap (the compact layout scrolls as a whole, and a scroller must not be nested in it): the notices just show in full.
    if (max == Dp.Infinity) { Column(Modifier.testTag(tag), content = content); return }
    val scroll = rememberScrollState()
    // One line of the text style the notices use, measured (a theme's line height can differ from the nominal one at large font).
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val line = with(LocalDensity.current) { measurer.measure(androidx.compose.ui.text.AnnotatedString("A"), style).size.height.toDp() }
    val capped = if (max == Dp.Infinity) max else line * maxOf(1, (max / line).toInt())
    Column {
        Column(Modifier.heightIn(max = capped).verticalScroll(scroll).testTag(tag), content = content)
        if (scroll.canScrollForward) Text(stringResource(R.string.confirm_more),
            style = MaterialTheme.typography.labelMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
            color = AppTheme.colors.accent, modifier = Modifier.testTag("${tag}_more"))
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
private fun ChatToolFailures(failures: List<ChatToolFailure>, modifier: Modifier = Modifier) {
    if (failures.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        failures.forEach { failure ->
            val tool = stringResource(AssistantToolId.fromWire(failure.result.name)?.title() ?: R.string.tool_failure_unknown)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(when (failure.result.outcome) {
                    ToolOutcome.TIMEOUT -> R.string.tool_failure_timeout
                    ToolOutcome.UNKNOWN_TOOL -> R.string.tool_failure_unavailable
                    else -> R.string.tool_failure_failed
                }, tool), color = AppTheme.colors.error)
                failure.query?.let { SelectionContainer { Text(stringResource(R.string.tool_failure_query, it)) } }
                SelectionContainer { Text(failure.result.text, modifier = Modifier.testTag("chat_tool_failure_detail")) }
            }
        }
    }
}

/** A declined call is not a failure: a quiet line per call, visible without opening the receipts. */
@Composable
private fun ChatToolDeclines(receipts: List<ChatToolReceipt>) {
    val declined = receipts.filter { it.result?.outcome == ToolOutcome.DECLINED }
    if (declined.isEmpty()) return
    Column(Modifier.testTag("chat_tool_declined"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        declined.forEach { receipt ->
            Text(stringResource(if (receipt.result.isNoAnswer()) R.string.tool_failure_no_answer else R.string.tool_failure_declined,
                stringResource(AssistantToolId.fromWire(receipt.call.name)?.title() ?: R.string.tool_failure_unknown)), color = AppTheme.colors.muted)
        }
    }
}

@Composable
private fun ChatToolReceipts(receipts: List<ChatToolReceipt>) {
    if (receipts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("chat_tool_receipts")) {
        Text(stringResource(R.string.chat_history_tool_results))
    }
    if (expanded) receipts.forEach { receipt ->
        val outcome = receipt.result?.outcome
        SelectionContainer { Text("${receipt.call.name}\n${receipt.call.arguments}\n${if (outcome == ToolOutcome.DECLINED) stringResource(if (receipt.result.isNoAnswer()) R.string.chat_receipt_no_answer else R.string.chat_receipt_declined) else outcome ?: "?"}\n${receipt.result?.text.orEmpty()}") }
    }
}

@Composable
private fun ChatSources(sources: List<ChatSource>) {
    if (sources.isNotEmpty()) Column(Modifier.testTag("chat_sources"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.chat_sources), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
        sources.forEach { source -> SelectionContainer { Text("${source.title}\n${source.url}") } }
    }
}

/** A declined result whose text is the fixed "no answer" one: the five minutes ran out, the owner did not decline. */
internal fun ru.adsrv.frpjump.agent.ToolResult?.isNoAnswer(): Boolean =
    this != null && outcome == ToolOutcome.DECLINED && text == ru.adsrv.frpjump.settings.ConfirmationTexts.NO_ANSWER
