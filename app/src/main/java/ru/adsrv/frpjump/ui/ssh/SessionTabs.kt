package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.ui.AppDropdownMenu
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.NameSetter
import ru.adsrv.frpjump.ssh.isolateName
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshState
import ru.adsrv.frpjump.ssh.TAB_NAME_MAX
import ru.adsrv.frpjump.ssh.TabNameCheck
import ru.adsrv.frpjump.ssh.TabNameProblem
import ru.adsrv.frpjump.ssh.checkTabName
import ru.adsrv.frpjump.ui.AppDialog
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field

/** What the session tabs do; ids are [SshSession.id]s. */
class SessionTabActions(
    val select: (Int) -> Unit,
    val close: (Int) -> Unit,
    val reconnect: (Int) -> Unit,
    /** An ended session again, as another user or with another method, in its tab. */
    val changeLogin: (Int) -> Unit,
    /** "+": another session like the selected one. */
    val add: () -> Unit,
    /** Long press on "+": the login dialog for the selected one's device and port. */
    val addAs: () -> Unit,
    /** "Log in as another user" from a session's menu: the login dialog for its device and port. */
    val logInAs: (Int) -> Unit,
    /** The owner's name for a session's tab ([ru.adsrv.frpjump.ssh.checkTabName]'s valid one), null: the automatic label again. */
    val rename: (Int, String?) -> Unit,
    /** "Use key login" for a session's user, device and port. */
    val keyLogin: (Int) -> Unit,
    /** Whether a tab's menu offers it: that login is still by password. */
    val offersKeyLogin: (Int) -> Boolean,
)

/**
 * The open sessions as a thin row of tabs: the profile's colour, the
 * owner's name for the tab or else user and device, the state when it is
 * not plain open, and "+" at the end. A tap on the selected tab (it has a
 * small ▾) or a long press on any tab opens its menu: Rename / Close /
 * Reconnect / Change login / Use key login / Log in as another user; the
 * same are TalkBack's custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionTabs(list: List<SshSession>, selectedId: Int, profileColors: Map<String, String>, actions: SessionTabActions) {
    val colors = AppTheme.colors
    val row = rememberLazyListState()
    val at = list.indexOfFirst { it.id == selectedId }
    // A tab picked or opened off screen comes into view.
    LaunchedEffect(selectedId, at) { if (at >= 0) row.animateScrollToItem(at) }
    var renamingId by rememberSaveable { mutableStateOf<Int?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            // At least; a larger font makes the row taller instead of clipping the label.
            .heightIn(min = TAB_ROW_HEIGHT)
            .background(colors.termBar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = row,
            // Not filling: "+" follows the last tab, and stays at the edge when they overflow.
            modifier = Modifier
                .weight(1f, fill = false)
                .selectableGroup(),
            contentPadding = PaddingValues(start = 8.dp, end = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(list, key = { it.id }) { s ->
                SessionTab(
                    s, tabLabel(s.user, s.device, s.port, s.number), on = s.id == selectedId,
                    dot = colors.termProfile(profileColors[s.profile] ?: "grey"), actions, onRename = { renamingId = s.id },
                )
            }
        }
        val description = stringResource(R.string.terminal_new_session)
        val asLabel = stringResource(R.string.terminal_new_session_as)
        Box(
            Modifier
                .size(TAB_ROW_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(role = Role.Button, onLongClickLabel = asLabel, onLongClick = actions.addAs, onClick = actions.add)
                .semantics {
                    contentDescription = description
                    customActions = listOf(CustomAccessibilityAction(asLabel) { actions.addAs(); true })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.onTerm, modifier = Modifier.size(20.dp))
        }
    }
    val renaming = list.firstOrNull { it.id == renamingId }
    if (renaming != null) {
        val current by renaming.tabName.collectAsStateWithLifecycle()
        TabNameDialog(
            current = current?.text,
            automatic = tabLabel(renaming.user, renaming.device, renaming.port, renaming.number),
            onSave = { name ->
                renamingId = null
                actions.rename(renaming.id, name)
            },
            onDismiss = { renamingId = null },
        )
    } else if (renamingId != null) {
        // The tab went (closed, its tunnel gone) while its dialog was up.
        renamingId = null
    }
}

/** One entry of a tab's menu, and the TalkBack custom action of the same name. */
private class TabAction(val label: String, val run: () -> Unit)

/**
 * Makes the dialog's window shrink above the keyboard (adjustResize) instead of being panned by it: a panned window pushes what
 * does not fit (the buttons) under the keyboard, a resized one gives the content the room that is left. Call it inside the dialog.
 */
@Composable
private fun ResizeWithKeyboard() {
    val view = LocalView.current
    val window = ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))?.window
    SideEffect { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
}

/** The dialog that names a tab: [current] is its name now (null: none), [automatic] the label shown when empty. */
@Composable
private fun TabNameDialog(current: String?, automatic: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(current.orEmpty(), TextRange(current.orEmpty().length)))
    }
    val check = checkTabName(field.text)
    val length = when (check) {
        is TabNameCheck.Valid -> check.name?.let { it.codePointCount(0, it.length) } ?: 0
        is TabNameCheck.Invalid -> check.length
    }
    val valid = check as? TabNameCheck.Valid
    val save = { valid?.let { onSave(it.name) }; Unit }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val spokenCount = stringResource(R.string.device_label_counter_spoken, length, TAB_NAME_MAX)
    // Not the stock alert layout: its fixed 24 dp paddings leave a few dp for the content in landscape at 200 % font with the keyboard
    // up. The title is the first line of the scrolling content, not a fixed slot; only the buttons keep their place (they are what must
    // stay reachable), and the room that is left goes to the field, which is brought into view whole.
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ResizeWithKeyboard()
        BoxWithConstraints {
            val tight = maxHeight < 360.dp
            Surface(
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = if (tight) 8.dp else 24.dp, bottom = if (tight) 4.dp else 16.dp)) {
                    val scroll = rememberScrollState()
                    val fieldInView = remember { BringIntoViewRequester() }
                    // The window shrinks above the keyboard (the scroll's room changes) and the name changes: the field itself stays whole
                    // in view. It is the minimal scroll, so the title stays too whenever both fit.
                    LaunchedEffect(scroll.maxValue, field.text) { runCatching { fieldInView.bringIntoView() } }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 16.dp),
                    ) {
                        Text(stringResource(R.string.terminal_tab_name_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AlertDialogDefaults.titleContentColor)
                        Field(
                            label = stringResource(R.string.terminal_tab_name_field),
                            value = field,
                            onValueChange = { field = it },
                            problem = (check as? TabNameCheck.Invalid)?.let {
                                stringResource(if (it.problem == TabNameProblem.TOO_LONG) R.string.terminal_tab_name_too_long else R.string.terminal_tab_name_bad_character)
                            },
                            note = stringResource(R.string.terminal_tab_name_automatic, automatic),
                            focusRequester = focus,
                            inputModifier = Modifier.bringIntoViewRequester(fieldInView),
                            onDone = { save() },
                            counter = {
                                Text(
                                    stringResource(R.string.device_label_counter, length, TAB_NAME_MAX),
                                    color = if (check is TabNameCheck.Invalid) AppTheme.colors.error else androidx.compose.ui.graphics.Color.Unspecified,
                                    modifier = Modifier.clearAndSetSemantics { contentDescription = spokenCount },
                                )
                            },
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = if (tight) 0.dp else 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = valid != null, onClick = { save() }) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(s: SshSession, automatic: String, on: Boolean, dot: Color, actions: SessionTabActions, onRename: () -> Unit) {
    val colors = AppTheme.colors
    val state by s.state.collectAsStateWithLifecycle()
    val custom by s.tabName.collectAsStateWithLifecycle()
    val label = chipLabel(automatic, custom)
    val byAssistant = custom?.setBy == NameSetter.ASSISTANT
    val byAssistantText = custom?.let { stringResource(R.string.terminal_tab_named_by_assistant, automatic, isolateName(it.text)) }
    val connecting = state == SshState.Connecting || state is SshState.Reconnecting
    val ended = state is SshState.Ended
    var menu by remember { mutableStateOf(false) }
    val stateWord = stringResource(
        when (state) {
            SshState.Connecting -> R.string.terminal_state_connecting
            SshState.Open -> R.string.terminal_state_open
            is SshState.Reconnecting -> R.string.terminal_state_reconnecting
            is SshState.HostKeyUnknown, is SshState.HostKeyChanged -> R.string.terminal_state_asks
            is SshState.Ended -> R.string.terminal_state_ended
        },
    )
    val menuLabel = stringResource(R.string.terminal_tab_menu)
    val renameLabel = stringResource(R.string.terminal_tab_rename)
    val closeLabel = stringResource(R.string.terminal_close_session)
    val reconnectLabel = stringResource(R.string.terminal_reconnect)
    val changeLoginLabel = stringResource(R.string.terminal_change_login)
    val keyLoginLabel = stringResource(R.string.key_login)
    val logInAsLabel = stringResource(R.string.terminal_new_session_as)
    // The menu and TalkBack's custom actions are the same list; "Rename…" first, the rest in their old order.
    val offered = buildList {
        add(TabAction(renameLabel, onRename))
        add(TabAction(closeLabel) { actions.close(s.id) })
        // Only for an ended one: on a live shell it would cut it off unasked.
        if (ended) {
            add(TabAction(reconnectLabel) { actions.reconnect(s.id) })
            add(TabAction(changeLoginLabel) { actions.changeLogin(s.id) })
        }
        if (actions.offersKeyLogin(s.id)) add(TabAction(keyLoginLabel) { actions.keyLogin(s.id) })
        // The visible path to the long press on "+": for the one that is still live.
        if (!ended) add(TabAction(logInAsLabel) { actions.logInAs(s.id) })
    }
    Box {
        Row(
            Modifier
                // At least: a larger font makes the tab taller instead of clipping its label.
                .heightIn(min = TAB_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(if (on) colors.termKey else Color.Transparent)
                .combinedClickable(
                    role = Role.Tab,
                    // The selected tab's tap is its menu (the ▾); another tab's is to pick it.
                    onClickLabel = if (on) menuLabel else null,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menu = true },
                    onClick = { if (on) menu = true else actions.select(s.id) },
                )
                .semantics {
                    selected = on
                    contentDescription = when {
                        custom == null -> label
                        byAssistant -> byAssistantText.orEmpty()
                        else -> "$label, $automatic"
                    } + ", $stateWord"
                    customActions = offered.map { a -> CustomAccessibilityAction(a.label) { a.run(); true } }
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            // Ended or failed: a red dot next to the profile's, so the profile still shows.
            if (ended) Box(Modifier.size(8.dp).background(colors.termError, CircleShape))
            if (connecting) {
                CircularProgressIndicator(color = colors.termMuted, strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                color = when {
                    connecting -> colors.termMuted.copy(alpha = 0.7f)
                    on -> colors.onTerm
                    else -> colors.termMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clearAndSetSemantics {},
            )
            // A hint that the selected tab has a menu; decorative, the tab itself says it.
            if (on) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = colors.onTerm.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp).testTag("tab_menu_arrow"),
                )
            }
        }
        AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // With a name of its own the tab still says who and where it is.
            if (custom != null) {
                Text(
                    automatic,
                    fontSize = 12.sp,
                    color = AppTheme.colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 6.dp).testTag("tab_menu_header"),
                )
            }
            // An assistant session the owner opened stays marked as the assistant's.
            if (s.openedBy == ru.adsrv.frpjump.ssh.SessionOpener.ASSISTANT) {
                Text(
                    stringResource(R.string.terminal_session_by_assistant),
                    fontSize = 12.sp,
                    color = AppTheme.colors.muted,
                    modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 6.dp).testTag("tab_menu_assistant"),
                )
            }
            offered.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = {
                    menu = false
                    a.run()
                })
            }
        }
    }
}

private val TAB_ROW_HEIGHT = 36.dp
private val TAB_HEIGHT = 30.dp
