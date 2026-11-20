package ru.adsrv.frpjump.ui.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import ru.adsrv.frpjump.devices.searchMatches
import ru.adsrv.frpjump.sessions.SessionRef
import ru.adsrv.frpjump.ui.SearchGlyph
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.MqttSession
import ru.adsrv.frpjump.mqtt.MqttSessions
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.TerminalGlyph
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.ui.SectionTitleRow

/**
 * The Sessions tab: the open terminal sessions, then the MQTT explorer
 * sessions; tap one to return to it, ✕ closes it with Undo (as a
 * terminal's tab and the explorer's "⋮" do). One closed, or whose tunnel
 * was disconnected, while the Undo is on screen is not listed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionsScreen(
    terminals: SshSessions,
    mqtt: MqttSessions,
    /** [ru.adsrv.frpjump.undo.PendingUndo.hidden]. */
    hidden: StateFlow<Set<String>>,
    close: SessionCloser,
    /** Profile id to its colour's name, for the marks. */
    profileColors: Map<String, String>,
    onOpenTerminal: (sessionId: Int) -> Unit,
    onOpenMqtt: (sessionId: Int) -> Unit,
    /** The assistant's own sessions: listed in a group of their own while the owner has them not as tabs. Null: none (tests). */
    assistant: ru.adsrv.frpjump.assistant.AssistantSessions? = null,
    profileNames: Map<String, String> = emptyMap(),
    onOpenDeviceTunnels: (String, String) -> Unit = { _, _ -> },
) {
    val allTerminals by terminals.sessions.collectAsStateWithLifecycle()
    val everyTerminal by terminals.all.collectAsStateWithLifecycle()
    val remembered by (assistant?.entries ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<ru.adsrv.frpjump.assistant.AssistantSessions.Entry>()) }).collectAsStateWithLifecycle()
    val problems by (assistant?.ownerProblems ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<ru.adsrv.frpjump.ssh.SessionKey, ru.adsrv.frpjump.assistant.OwnerSessionProblem>()) }).collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val allExplorers by mqtt.sessions.collectAsStateWithLifecycle()
    val gone by hidden.collectAsStateWithLifecycle()
    val list = remember(allTerminals, gone) {
        allTerminals.filterNot { sessionHidden(gone, SessionKind.TERMINAL, it.id, TunnelRef(it.profile, it.device, it.port)) }
    }
    val explorers = remember(allExplorers, gone) {
        allExplorers.filterNot { sessionHidden(gone, SessionKind.MQTT, it.id, TunnelRef(it.profile, it.device, it.port)) }
    }
    // The assistant's sessions as its records say (the records are the ONE list, each once), except those the owner has opened as tabs (they are in the list above).
    val terminalTitle = stringResource(R.string.terminal)
    val mine = remember(everyTerminal, allTerminals, remembered, gone, problems, terminalTitle) {
        remembered.mapNotNull { entry ->
            val live = everyTerminal.firstOrNull { it.tabNameKey() == entry.key }
            val id = live?.id ?: entry.id
            if (allTerminals.any { it.id == live?.id } || sessionHidden(gone, SessionKind.TERMINAL, id, TunnelRef(entry.key.profile, entry.key.device, entry.key.port))) null
            else {
                val number = live?.number ?: ru.adsrv.frpjump.ssh.tmuxNumber(entry.key.session).toInt().coerceAtLeast(1)
                AssistantRow(entry.id, assistantSessionTitle(entry.name, number, terminalTitle), live, id, entry.key.profile, entry.key.device, entry.key.user, entry.key.port, problems[entry.key])
            }
        }
    }
    val colors = AppTheme.colors
    val assistantLabel = stringResource(R.string.assistant_title)
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<SessionRef>()) }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    fun matches(vararg values: String): Boolean = query.isBlank() || values.any { searchMatches(it, query.trim()) }
    val shownTerminals = list.filter { matches(terminalSessionLabel(it), it.title, it.device, it.user, it.profile, profileNames[it.profile].orEmpty()) }
    val shownMqtt = explorers.filter { matches(it.device, it.port.toString(), it.profile, profileNames[it.profile].orEmpty()) }
    val shownAssistant = mine.filter { matches(it.title, it.device, it.user, it.profile, profileNames[it.profile].orEmpty()) }
    val shownRefs = (shownTerminals.map { SessionRef(SessionKind.TERMINAL, it.id) } +
        shownMqtt.map { SessionRef(SessionKind.MQTT, it.id) } + shownAssistant.map { SessionRef(SessionKind.TERMINAL, it.sessionId) }).toSet()
    fun toggle(ref: SessionRef) { selected = if (ref in selected) selected - ref else selected + ref }
    LaunchedEffect(shownRefs) { selected = selected intersect shownRefs }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).statusBarsPadding()) {
        if (selected.isEmpty()) {
            val focus = LocalFocusManager.current
            val requester = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val searchLabel = stringResource(R.string.sessions_search)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).testTag("sessions_search_surface").heightIn(min = 48.dp)
                .background(colors.card, RoundedCornerShape(ru.adsrv.frpjump.ui.HeaderControlRadius))
                .border(BorderStroke(1.dp, colors.outline), RoundedCornerShape(ru.adsrv.frpjump.ui.HeaderControlRadius)).clickable { requester.requestFocus(); keyboard?.show() }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(SearchGlyph, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp).testTag("sessions_search_icon"))
                BasicTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = colors.ink), cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 8.dp).focusRequester(requester).testTag("sessions_search").semantics { contentDescription = searchLabel },
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(stringResource(R.string.sessions_search), color = colors.muted, fontSize = 16.sp, modifier = Modifier.testTag("sessions_search_hint"))
                            Box(Modifier.testTag("sessions_search_value")) { field() }
                        }
                    })
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.connections_clear_query), tint = colors.muted) }
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).testTag("sessions_selection"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close), tint = colors.ink) }
                Text(stringResource(R.string.tunnels_selected_count, (selected intersect shownRefs).size), modifier = Modifier.weight(1f), color = colors.ink)
                IconButton(onClick = { selected = shownRefs }, modifier = Modifier.testTag("sessions_select_all")) { Icon(ru.adsrv.frpjump.ui.SelectAllGlyph, contentDescription = stringResource(R.string.tunnels_select_all), tint = colors.ink) }
                IconButton(onClick = { close.closeSelected(selected intersect shownRefs); selected = emptySet() }, modifier = Modifier.testTag("sessions_close_selected"), enabled = (selected intersect shownRefs).isNotEmpty()) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sessions_close_selected, (selected intersect shownRefs).size), tint = colors.error) }
            }
        }
    LazyColumn(
        Modifier
            .fillMaxSize().testTag("sessions_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (list.isEmpty() && explorers.isEmpty() && mine.isEmpty()) {
            item(key = "empty") {
                Surface(color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SessionTile(dot = null) { TerminalIcon() }
                        Text(stringResource(R.string.sessions_empty), fontSize = 14.sp, color = colors.muted, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (shownRefs.isEmpty() && (list.isNotEmpty() || explorers.isNotEmpty() || mine.isNotEmpty())) item {
            Text(stringResource(R.string.sessions_no_match), color = colors.muted, modifier = Modifier.padding(4.dp))
        }
        items(shownTerminals, key = { "t${it.id}" }) { session ->
            val ref = SessionRef(SessionKind.TERMINAL, session.id)
            SelectableTunnelRow(listOfNotNull(terminalSessionLabel(session), profileLabel(session.profile, profileNames), assistantLabel.takeIf { session.openedBy == ru.adsrv.frpjump.ssh.SessionOpener.ASSISTANT }).joinToString(", "), selected.isNotEmpty(), ref in selected, { toggle(ref) }) {
                TerminalLine(session, profileColors[session.profile], profileLabel(session.profile, profileNames), session.tabNameKey()?.let { problems[it] },
                    onRecover = { onOpenDeviceTunnels(session.profile, session.device) }, onClose = { close.terminal(session.id) }, onLongClick = { toggle(ref) }, selecting = selected.isNotEmpty()) { onOpenTerminal(session.id) }
            }
        }
        items(shownMqtt, key = { "m${it.id}" }) { session ->
            val ref = SessionRef(SessionKind.MQTT, session.id)
            SelectableTunnelRow("${session.device}:${session.port}, ${profileLabel(session.profile, profileNames)}", selected.isNotEmpty(), ref in selected, { toggle(ref) }) {
                MqttLine(session, profileColors[session.profile], onClose = { close.mqtt(session.id) }, onLongClick = { toggle(ref) }, selecting = selected.isNotEmpty()) { onOpenMqtt(session.id) }
            }
        }
        // The assistant's remembered sessions share the list and retain their own exact identity.
        items(shownAssistant, key = { "a${it.id}" }) { row ->
                val ref = SessionRef(SessionKind.TERMINAL, row.sessionId)
                SelectableTunnelRow("${row.title}, ${row.user}@${row.device}:${row.port}, ${profileLabel(row.profile, profileNames)}, $assistantLabel", selected.isNotEmpty(), ref in selected, { toggle(ref) }) {
                    AssistantLine(row, onOpen = { assistant?.let { a -> scope.launch { a.show(row.id)?.let { onOpenTerminal(it.id) } } } }, onClose = { close.terminal(row.sessionId) }, onLongClick = { toggle(ref) }, selecting = selected.isNotEmpty(), profileName = profileLabel(row.profile, profileNames), profileColor = profileColors[row.profile], onRecover = { onOpenDeviceTunnels(row.profile, row.device) })
                }
        }
    }
    }
}

/** One remembered assistant session: [session] is null while it is remembered and not attached (detached). */
internal class AssistantRow(val id: Int, val title: String, val session: SshSession?, /** The id the owner's Close and the Undo use: the live session's, else the record's. */ val sessionId: Int = id,
    val profile: String = session?.profile.orEmpty(), val device: String = session?.device.orEmpty(), val user: String = session?.user.orEmpty(), val port: Int = session?.port ?: 0,
    val problem: ru.adsrv.frpjump.assistant.OwnerSessionProblem? = null)

internal fun terminalSessionLabel(session: SshSession, name: ru.adsrv.frpjump.ssh.TabName? = session.tabName.value): String {
    val automatic = ru.adsrv.frpjump.ui.ssh.tabLabel(session.user, session.device, session.port, session.number)
    return sessionIdentityLabel(automatic, name)
}

internal fun assistantSessionTitle(name: ru.adsrv.frpjump.ssh.TabName?, number: Int, terminal: String): String {
    val title = name?.text?.let { ru.adsrv.frpjump.ssh.isolateName(it) } ?: terminal
    return if (number > 1) "$title · $number" else title
}

private fun sessionIdentityLabel(automatic: String, name: ru.adsrv.frpjump.ssh.TabName?): String {
    val label = ru.adsrv.frpjump.ui.ssh.chipLabel(automatic, name)
    return if (name?.setBy == ru.adsrv.frpjump.ssh.NameSetter.OWNER) "$label · $automatic" else label
}

@Composable
internal fun AssistantLine(row: AssistantRow, onOpen: () -> Unit, onClose: () -> Unit, onLongClick: () -> Unit = {}, selecting: Boolean = false,
    profileName: String = row.profile, profileColor: String? = null, onRecover: () -> Unit = {}) {
    val colors = AppTheme.colors
    val live = row.session?.state?.collectAsStateWithLifecycle()?.value
    val (stateText, stateColor) = if (live == null) stringResource(R.string.terminal_state_detached) to colors.muted else terminalStateText(live)
    val identity = listOfNotNull(row.device.takeIf { it.isNotBlank() }?.let { "${row.user}@$it:${row.port}" }, profileName.takeIf { it.isNotBlank() }).joinToString(" · ")
    val problem = row.problem?.let { stringResource(ru.adsrv.frpjump.ui.ownerSessionProblemText(it)) }
    SessionLine(row.title, stateText, stateColor, profileColor, onClose, onLongClick, onOpen, selecting, "sessions_assistant_${row.id}",
        identity = identity, problem = problem, onRecover = if (problem != null) onRecover else null, wrapTitle = true, assistantOwned = true) { TerminalIcon() }
}

@Composable
private fun TerminalLine(session: SshSession, profileColor: String?, profileName: String, problem: ru.adsrv.frpjump.assistant.OwnerSessionProblem?, onRecover: () -> Unit, onClose: () -> Unit, onLongClick: () -> Unit, selecting: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val state by session.state.collectAsStateWithLifecycle()
    val (text, color) = terminalStateText(state)
    val assistantOwned = session.openedBy == ru.adsrv.frpjump.ssh.SessionOpener.ASSISTANT
    val detail = if (assistantOwned) "${session.user}@${session.device}:${session.port} · $profileName" else ""
    val failure = problem?.let { stringResource(ru.adsrv.frpjump.ui.ownerSessionProblemText(it)) }
    val name by session.tabName.collectAsStateWithLifecycle()
    val title = if (assistantOwned) assistantSessionTitle(name, session.number, stringResource(R.string.terminal)) else terminalSessionLabel(session, name)
    SessionLine(title, text, color, profileColor, onClose, onLongClick, onClick, selecting, "sessions_terminal_${session.id}",
        identity = detail, problem = failure, onRecover = if (failure != null) onRecover else null, wrapTitle = assistantOwned, assistantOwned = assistantOwned) { TerminalIcon() }
}

@Composable
internal fun terminalStateText(state: SshState): Pair<String, Color> {
    val colors = AppTheme.colors
    return when (state) {
        SshState.Connecting -> stringResource(R.string.terminal_state_connecting) to colors.muted
        SshState.Open -> stringResource(R.string.terminal_state_open) to colors.accent
        is SshState.Reconnecting -> stringResource(R.string.terminal_state_reconnecting) to colors.muted
        is SshState.Ended -> stringResource(R.string.terminal_state_ended) to colors.muted
        else -> stringResource(R.string.terminal_state_asks) to colors.warning
    }
}

/** An explorer session: its device and port, as the explorer's bar names it. */
@Composable
private fun MqttLine(session: MqttSession, profileColor: String?, onClose: () -> Unit, onLongClick: () -> Unit, selecting: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val state by session.state.collectAsStateWithLifecycle()
    val (text, color) = when (state) {
        MqttState.Connecting -> stringResource(R.string.terminal_state_connecting) to colors.muted
        is MqttState.Open -> stringResource(R.string.mqtt_session_open) to colors.accent
        MqttState.Reconnecting -> stringResource(R.string.mqtt_reconnecting) to colors.warning
        is MqttState.NeedsLogin -> stringResource(R.string.mqtt_state_login) to colors.warning
        is MqttState.Failed -> stringResource(R.string.mqtt_session_not_connected) to colors.error
        MqttState.Ended -> stringResource(R.string.mqtt_session_ended) to colors.muted
    }
    SessionLine("${session.device}:${session.port}", text, color, profileColor, onClose, onLongClick, onClick, selecting, "sessions_mqtt_${session.id}") {
        Text(stringResource(R.string.kind_mqtt_tile), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionLine(title: String, state: String, stateColor: Color, profileColor: String?, onClose: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit, selecting: Boolean, tag: String, identity: String = "", problem: String? = null, onRecover: (() -> Unit)? = null, wrapTitle: Boolean = false, assistantOwned: Boolean = false, glyph: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Surface(color = colors.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag(tag).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SessionTile(dot = colors.profile(profileColor ?: "grey"), assistantOwned = assistantOwned, glyph = glyph)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = if (wrapTitle) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                    if (identity.isNotBlank()) Text(identity, fontSize = 12.sp, color = colors.muted, modifier = Modifier.testTag("$tag-identity"))
                    Text(state, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = stateColor)
                }
                if (selecting) Spacer(Modifier.size(48.dp)) else IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("$tag-close")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.session_close_named, listOf(title, identity).filter { it.isNotBlank() }.joinToString(" · ")), tint = colors.muted, modifier = Modifier.size(20.dp))
                }
            }
            if (problem != null) Text(problem, fontSize = 12.sp, color = colors.warning, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("$tag-problem"))
            if (onRecover != null && !selecting) androidx.compose.material3.TextButton(onClick = onRecover, modifier = Modifier.testTag("sessions_assistant_recover")) { Text(stringResource(R.string.assistant_session_tunnels)) }
        }
    }
}

@Composable
internal fun TerminalIcon() = Icon(TerminalGlyph, contentDescription = null, tint = AppTheme.colors.ink, modifier = Modifier.size(22.dp))

/** The session's glyph on a tile; [dot], the profile's colour, in the corner. */
@Composable
internal fun SessionTile(dot: Color?, assistantOwned: Boolean = false, glyph: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .background(colors.tile, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            glyph()
        }
        if (assistantOwned) {
            val label = stringResource(R.string.assistant_title)
            Text("✦", color = colors.accent, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopEnd).clearAndSetSemantics { contentDescription = label }.testTag("session_assistant_marker"))
        }
        dot?.let {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(colors.card, CircleShape)
                    .padding(2.dp)
                    .background(it, CircleShape),
            )
        }
    }
}
