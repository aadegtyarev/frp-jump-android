package ru.adsrv.frpjump.ui.mqtt

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.api.relativeAge
import ru.adsrv.frpjump.mqtt.MqttFailure
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.mqtt.MqttVersion
import ru.adsrv.frpjump.mqtt.SnapshotEnd
import ru.adsrv.frpjump.mqtt.SnapshotState
import ru.adsrv.frpjump.mqtt.MAX_TOPIC_BYTES
import ru.adsrv.frpjump.mqtt.TopicNode
import ru.adsrv.frpjump.mqtt.TopicProblem
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.ValueLine
import ru.adsrv.frpjump.mqtt.LastChange
import ru.adsrv.frpjump.mqtt.hitLabel
import ru.adsrv.frpjump.mqtt.lastChange
import ru.adsrv.frpjump.mqtt.nodeLabel
import ru.adsrv.frpjump.mqtt.pathBarSeparator
import ru.adsrv.frpjump.mqtt.publishAtLevel
import ru.adsrv.frpjump.mqtt.shownPath
import ru.adsrv.frpjump.mqtt.middleEllipsis
import ru.adsrv.frpjump.ui.HomeGlyph
import ru.adsrv.frpjump.mqtt.pathAtPart
import ru.adsrv.frpjump.mqtt.pathBarLabels
import ru.adsrv.frpjump.mqtt.valueLine
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AppTopBar
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.FileGlyph
import ru.adsrv.frpjump.ui.FolderGlyph
import ru.adsrv.frpjump.ui.PinGlyph
import ru.adsrv.frpjump.mqtt.FilterOffer
import ru.adsrv.frpjump.mqtt.PatternProblem
import ru.adsrv.frpjump.ui.SearchBar
import ru.adsrv.frpjump.ui.SearchGlyph
import ru.adsrv.frpjump.ui.StatusBanner
import ru.adsrv.frpjump.ui.ageText
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.files.PathBar
import ru.adsrv.frpjump.ui.files.SecondaryButton
import ru.adsrv.frpjump.ui.PlusGlyph
import ru.adsrv.frpjump.ui.Segmented
import ru.adsrv.frpjump.mqtt.SubscriptionRow
import ru.adsrv.frpjump.mqtt.SubscriptionTarget
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.text.NumberFormat
import java.time.Duration

/** Characters of a path the search hint shows at most; a longer one is cut in the middle. */
private const val HINT_PATH_CHARS = 28

/** A value changed this recently (after the level was shown) has its age in the accent colour; branches never do. */
private const val HIGHLIGHT_MS = 2_000L

/**
 * The MQTT explorer of one session: the path bar, the open level's
 * branches and topics with their values, search over the level and below.
 * Back goes up a level, then leaves; the session stays in the app.
 */
@Composable
fun MqttScreen(vm: MqttViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val focused by vm.focused.collectAsStateWithLifecycle()
    val published by vm.published.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val rules by vm.commandRules.collectAsStateWithLifecycle()
    // Titles in the phone's language (the app's own when set): `ru-RU` → `ru`.
    val language = LocalConfiguration.current.locales[0].language
    LaunchedEffect(language) { vm.language.value = language }
    val colors = AppTheme.colors
    val session = vm.session
    var menu by remember { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var pinning by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    var subscribing by rememberSaveable { mutableStateOf(false) }
    var plugins by rememberSaveable { mutableStateOf(false) }
    // The pin whose mask is being changed, by its filter.
    var masking by rememberSaveable { mutableStateOf<String?>(null) }
    // "Publish here…": the levels of the place it was asked at (a Bundle holds no MqttPath).
    var writingAt by rememberSaveable { mutableStateOf<List<String>?>(null) }
    // The refusal the dialog was answered or dismissed for (compared by identity: each refusal is a new
    // state object, also when it reads the same); dismissed, the banner offers it again.
    var loginDone by remember { mutableStateOf<MqttState?>(null) }
    val closeSearch = {
        searching = false
        focusSearch = false
        vm.query.value = ""
        vm.searchWhole.value = false
    }
    val context = LocalContext.current
    // Leaving keeps the session: the first time, say where it is.
    val leave = {
        vm.leftRunning()?.let { live ->
            context.notice(context.resources.getQuantityString(R.plurals.sessions_left_running, live, live), NoticeKind.INFO, long = true)
        }
        onBack()
    }
    BackHandler { if (!vm.up()) leave() }
    // Declared later, so it comes first: Back closes the search before it goes up a level.
    BackHandler(enabled = searching) { closeSearch() }
    // On another tab Back returns to the tree first, as the app's bottom bar returns to its first tab.
    BackHandler(enabled = state.shownTab != ExplorerTab.TREE && !searching) { vm.tab.value = ExplorerTab.TREE }
    // Inside a device Back returns to the devices list first.
    BackHandler(enabled = state.shownTab == ExplorerTab.WB_DEVICES && state.wbDeviceId != null && !searching) { vm.closeWbDevice() }
    val open = state.state != MqttState.Ended
    // Where a subscription's tap leads: its topic's card (over the list), its branch, or its pattern searched.
    val goTo: (SubscriptionRow) -> Unit = { row ->
        when (val target = row.target) {
            is SubscriptionTarget.Topic -> vm.openCard(target.path)
            is SubscriptionTarget.Branch -> {
                closeSearch()
                vm.open(target.path)
                target.search?.let {
                    vm.query.value = it
                    searching = true
                }
            }
            is SubscriptionTarget.Search -> {
                vm.tab.value = ExplorerTab.TREE
                vm.query.value = target.pattern
                vm.searchWhole.value = true
                searching = true
            }
        }
    }

    val target = session?.let { "${it.device}:${it.port}" }.orEmpty()
    Scaffold(
        containerColor = colors.ground,
        topBar = {
            if (searching) {
                SearchBar(
                    query,
                    onQuery = { vm.query.value = it },
                    onClose = closeSearch,
                    focus = focusSearch,
                    onFocused = { focusSearch = false },
                    label = stringResource(R.string.mqtt_search),
                    // Says where it looks: the open branch and below, or the whole tree.
                    hint = shownPath(state.searchAt)?.let { stringResource(R.string.mqtt_search_in, middleEllipsis(it, HINT_PATH_CHARS)) }
                        ?: stringResource(R.string.mqtt_search_whole),
                )
            } else {
                AppTopBar(
                    title = target.ifEmpty { stringResource(R.string.kind_mqtt) },
                    subtitle = statusWord(state.state),
                    onBack = leave,
                    actions = {
                        IconButton(onClick = {
                            // Search looks in the tree.
                            vm.tab.value = ExplorerTab.TREE
                            searching = true
                            focusSearch = true
                        }) {
                            Icon(SearchGlyph, contentDescription = stringResource(R.string.mqtt_search), tint = colors.ink, modifier = Modifier.size(22.dp))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mqtt_actions))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mqtt_publish_here)) },
                                    enabled = state.state != MqttState.Ended,
                                    onClick = {
                                        menu = false
                                        writingAt = ArrayList(state.path.levels)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mqtt_refresh_tree)) },
                                    enabled = state.state is MqttState.Open,
                                    onClick = {
                                        menu = false
                                        vm.refreshTree()
                                    },
                                )
                                if (session != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.mqtt_plugins_menu)) },
                                        onClick = {
                                            menu = false
                                            plugins = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_close_session)) },
                                    enabled = state.state != MqttState.Ended,
                                    onClick = {
                                        menu = false
                                        vm.closeSession()
                                        onBack()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (state.shownTab == ExplorerTab.SUBSCRIPTIONS && !searching) {
                val closedReason = stringResource(R.string.mqtt_closed)
                // A closed session cannot subscribe: the button is muted and a tap says why.
                FloatingActionButton(
                    onClick = { if (open) subscribing = true else context.notice(closedReason, NoticeKind.INFO) },
                    containerColor = if (open) colors.strong else colors.tile,
                    contentColor = if (open) colors.onStrong else colors.muted,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.semantics { if (!open) stateDescription = closedReason },
                ) { Icon(PlusGlyph, contentDescription = stringResource(R.string.mqtt_subs_add), modifier = Modifier.size(22.dp)) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            // A search is the tree's own mode: the tabs come back when it closes.
            if (!searching) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Segmented(state.tabs, state.shownTab, label = { tabLabel(it) }, onPick = { vm.tab.value = it })
                }
            }
            if (state.shownTab == ExplorerTab.WB_DEVICES && !searching) {
                Banners(state, session?.device.orEmpty(), session?.port ?: 0, onRetry = vm::retry, onLogin = { loginDone = null }, onPinBranch = { pinning = true })
                WbDevicesTab(
                    state,
                    language,
                    padding,
                    onOpen = vm::openWbDevice,
                    onBack = vm::closeWbDevice,
                    // The ordinary card of the control's topic, over the tab.
                    onCard = { vm.openCard(it.path) },
                    onWrite = vm::writeWb,
                )
                return@Column
            }
            if (state.shownTab == ExplorerTab.SUBSCRIPTIONS && !searching) {
                Banners(state, session?.device.orEmpty(), session?.port ?: 0, onRetry = vm::retry, onLogin = { loginDone = null }, onPinBranch = { pinning = true })
                SubscriptionsList(
                    state,
                    open = open,
                    padding = padding,
                    onTap = goTo,
                    // Gone at once with Undo on the shared notice, as Unpin on a card.
                    onUnpin = vm::unpinFilter,
                    onChangeMask = { masking = it.filter },
                )
                return@Column
            }
            val empty = stringResource(R.string.mqtt_empty_level)
            val labels = pathBarLabels(state.path, "", empty)
            val leadingSlash = stringResource(R.string.mqtt_leading_slash_spoken)
            PathBar(
                labels,
                onOpen = { vm.open(pathAtPart(state.path, it)) },
                onType = { typing = true },
                typeLabel = stringResource(R.string.mqtt_go_to_topic),
                separatorBefore = { pathBarSeparator(state.path, it) },
                rootIcon = HomeGlyph,
                spoken = { i -> leadingSlash.takeIf { i == 1 && state.path.levels.firstOrNull() == "" } },
            )
            SnapshotLine(state)
            Banners(state, session?.device.orEmpty(), session?.port ?: 0, onRetry = vm::retry, onLogin = { loginDone = null }, onPinBranch = { pinning = true })
            if (searching && query.isNotBlank()) {
                // The card opens over the hits, so closing it returns to them.
                SearchHits(
                    state,
                    query.trim(),
                    padding,
                    onOpen = { hit -> vm.openCard(MqttPath(state.searchAt.levels + hit.relative.levels)) },
                    onSearchWhole = { vm.searchWhole.value = true },
                    onSubscribe = { filter ->
                        vm.subscribeFilter(filter)
                        context.notice(context.getString(R.string.mqtt_pinned_notice, filter), NoticeKind.DONE)
                    },
                    // Gone at once with Undo on the shared notice, as Unpin on a card.
                    onUnsubscribe = vm::unpinFilter,
                )
            } else {
                Level(state, focused, padding, onOpen = vm::open, onCard = vm::openCard, onWriteHere = { writingAt = ArrayList(state.path.levels) })
            }
        }
    }

    val s = state.state
    if (s is MqttState.NeedsLogin && loginDone !== s) {
        MqttLoginDialog(
            target = target,
            user = s.user,
            wrong = s.wrong,
            onConnect = { user, password, remember ->
                // The next refusal is a new state object, so the dialog comes back for it.
                loginDone = s
                vm.login(user, password, remember)
            },
            onDismiss = { loginDone = s },
        )
    }
    state.card?.let { card ->
        TopicCard(
            card = card,
            canPin = state.state != MqttState.Ended,
            state = state.state,
            onPin = { vm.pin(card.path, it) },
            onUnpin = { vm.unpin(card.path, it) },
            published = published,
            rules = rules,
            onPublish = vm::publish,
            onOpenBranch = {
                // Going somewhere ends the search it was found by (as a typed path does).
                closeSearch()
                vm.closeCard()
                vm.open(card.path)
            },
            onDismiss = vm::closeCard,
        )
    }
    writingAt?.let { levels ->
        PublishSheet(
            start = publishAtLevel(MqttPath(levels)),
            state = state.state,
            published = published,
            rules = rules,
            onPublish = vm::publish,
            onDismiss = { writingAt = null },
        )
    }
    if (plugins && session != null) {
        PluginsSheet(session.profile, session.connectionId, subtitle = target, onDismiss = { plugins = false })
    }
    if (typing) {
        TopicDialog(
            title = stringResource(R.string.mqtt_go_to_topic),
            initial = state.path.format(),
            confirm = stringResource(R.string.files_go),
            onGo = { text ->
                vm.openTyped(text).also {
                    if (it == null) {
                        typing = false
                        closeSearch()
                    }
                }
            },
            onDismiss = { typing = false },
        )
    }
    if (subscribing && !open) {
        // Closed while it was open: nothing can be subscribed any more; say why rather than vanish.
        LaunchedEffect(Unit) {
            subscribing = false
            context.notice(context.getString(R.string.mqtt_closed), NoticeKind.INFO)
        }
    } else if (subscribing) {
        SubscribeDialog(
            onSubscribe = { text ->
                vm.subscribeTyped(text).also {
                    if (it == null) {
                        subscribing = false
                        context.notice(context.getString(R.string.mqtt_pinned_notice, text), NoticeKind.DONE)
                    }
                }
            },
            onDismiss = { subscribing = false },
        )
    }
    masking?.let { filter ->
        // Gone meanwhile (unpinned, or the session closed): nothing to change.
        val row = state.subscriptions.firstOrNull { it.filter == filter }
        if (row == null || !open) {
            LaunchedEffect(filter) {
                masking = null
                if (!open) context.notice(context.getString(R.string.mqtt_closed), NoticeKind.INFO)
            }
        } else {
            ChangeMaskSheet(
                row,
                state.pins,
                onPick = { mask ->
                    vm.changeMask(filter, mask)?.let { context.notice(context.getString(R.string.mqtt_pinned_notice, it), NoticeKind.DONE) }
                },
                onDismiss = { masking = null },
            )
        }
    }
    if (pinning) {
        TopicDialog(
            title = stringResource(R.string.mqtt_pin_branch),
            initial = state.path.format(),
            confirm = stringResource(R.string.mqtt_pin),
            onGo = { text -> vm.pinBranch(text).also { if (it == null) pinning = false } },
            onDismiss = { pinning = false },
        )
    }
}

@Composable
private fun tabLabel(tab: ExplorerTab): String = when (tab) {
    ExplorerTab.TREE -> stringResource(R.string.mqtt_tab_tree)
    ExplorerTab.SUBSCRIPTIONS -> stringResource(R.string.mqtt_tab_subscriptions)
    ExplorerTab.WB_DEVICES -> stringResource(R.string.wb_tab_devices)
}

@Composable
private fun statusWord(state: MqttState): String = when (state) {
    MqttState.Connecting -> stringResource(R.string.terminal_state_connecting)
    is MqttState.NeedsLogin -> stringResource(R.string.mqtt_state_login)
    is MqttState.Open -> stringResource(if (state.version == MqttVersion.V5) R.string.mqtt_version_5 else R.string.mqtt_version_311)
    MqttState.Reconnecting -> stringResource(R.string.mqtt_reconnecting)
    is MqttState.Failed -> stringResource(R.string.state_not_connected)
    MqttState.Ended -> stringResource(R.string.mqtt_state_closed)
}

/** Under the path bar while the snapshot is read: a bar and the count of topics so far. */
@Composable
private fun SnapshotLine(state: MqttUiState) {
    val colors = AppTheme.colors
    if (state.snapshot != SnapshotState.Reading || state.state !is MqttState.Open) {
        Box(Modifier.fillMaxWidth().height(2.dp))
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        LinearProgressIndicator(color = colors.accent, trackColor = colors.ground, modifier = Modifier.fillMaxWidth())
        Text(
            pluralStringResource(R.plurals.mqtt_reading_tree, state.topicCount, count(state.topicCount)),
            fontSize = 12.sp,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

/** The connection's state and what the tree is missing, each with what may fix it. */
@Composable
private fun Banners(state: MqttUiState, device: String, port: Int, onRetry: () -> Unit, onLogin: () -> Unit, onPinBranch: () -> Unit) {
    when (val s = state.state) {
        MqttState.Connecting -> StatusBanner(stringResource(R.string.mqtt_connecting), busy = true)
        MqttState.Reconnecting -> StatusBanner(stringResource(R.string.mqtt_reconnecting), busy = true)
        is MqttState.NeedsLogin -> StatusBanner(stringResource(R.string.mqtt_login_needed), action = stringResource(R.string.mqtt_log_in), onAction = onLogin)
        is MqttState.Failed -> StatusBanner(
            text = when (s.reason) {
                MqttFailure.NO_BROKER -> stringResource(R.string.mqtt_no_broker, port, device)
                MqttFailure.NO_COMMON_VERSION -> stringResource(R.string.mqtt_no_common_version)
                MqttFailure.REFUSED -> stringResource(R.string.mqtt_refused)
            },
            detail = s.detail,
            error = true,
            action = stringResource(R.string.retry),
            onAction = onRetry,
        )
        MqttState.Ended -> StatusBanner(stringResource(if (state.closedByTunnel) R.string.mqtt_closed_by_tunnel else R.string.mqtt_closed))
        is MqttState.Open -> Unit
    }
    when {
        state.snapshot == SnapshotState.Refused ->
            StatusBanner(stringResource(R.string.mqtt_snapshot_refused), action = stringResource(R.string.mqtt_pin_branch), onAction = onPinBranch)
        state.limitReached || state.snapshot == SnapshotState.Done(SnapshotEnd.LIMIT) ->
            StatusBanner(stringResource(R.string.mqtt_limit_reached, count(state.topicCount)))
        state.snapshot == SnapshotState.Done(SnapshotEnd.TIMED_OUT) -> StatusBanner(stringResource(R.string.mqtt_snapshot_timed_out))
    }
}

/** The open level's rows; empty, it says why. A focused topic is scrolled to. */
@Composable
private fun Level(
    state: MqttUiState,
    focused: MqttPath?,
    padding: PaddingValues,
    onOpen: (MqttPath) -> Unit,
    onCard: (MqttPath) -> Unit,
    onWriteHere: () -> Unit,
) {
    val colors = AppTheme.colors
    val list = rememberLazyListState()
    // A new level starts at its top; a revealed topic scrolls into view once its row is there.
    LaunchedEffect(state.path) { list.scrollToItem(0) }
    val focusIndex = focused?.let { f -> state.rows.indexOfFirst { it.node.path == f } } ?: -1
    LaunchedEffect(focused, focusIndex >= 0) { if (focusIndex >= 0) list.animateScrollToItem(focusIndex) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
    ) {
        // `$SYS` is always offered at the root, so only a live session there is never empty.
        if (state.rows.isEmpty()) item(key = "empty") { EmptyLevel(state, onWriteHere) }
        items(state.rows, key = { rowKey(it.node.path) }) { row ->
            TopicRowView(
                row = row,
                now = state.now,
                shownSince = state.shownSince,
                // A node with a value opens its card (which opens its branch); a branch only, its level.
                onClick = { if (row.opensLevel) onOpen(row.node.path) else onCard(row.node.path) },
                onCard = { onCard(row.node.path) },
            )
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 70.dp))
        }
    }
}

@Composable
private fun EmptyLevel(state: MqttUiState, onWriteHere: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.state == MqttState.Connecting || state.snapshot == SnapshotState.Reading) {
            CircularProgressIndicator(color = colors.accent)
            return@Column
        }
        Text(
            stringResource(if (state.branchRefused) R.string.mqtt_branch_refused else R.string.mqtt_no_messages),
            color = colors.muted,
            fontSize = 14.sp,
        )
        // Nothing here yet: a topic of this level can still be written, which is also how one is created.
        if (!state.path.isRoot && state.state != MqttState.Ended) {
            SecondaryButton(stringResource(R.string.mqtt_publish_here), null, enabled = true, Modifier, onClick = onWriteHere)
        }
    }
}

/**
 * A branch (folder tile: opens; "⋮" opens its card) or a topic (value
 * tile: opens its card): name, the pin mark, the value in one line, the
 * newest change's age in the accent colour for a moment after it changed,
 * the retained mark and the count of topics below.
 */
@Composable
private fun TopicRowView(row: TopicRow, now: Long, shownSince: Long, onClick: () -> Unit, onCard: () -> Unit) {
    val colors = AppTheme.colors
    val node = row.node
    val value = node.value
    val change = lastChange(node)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(if (row.opensLevel) R.string.files_open else R.string.mqtt_open_card),
                onClick = onClick,
            )
            .padding(start = 16.dp, end = if (row.isBranch) 0.dp else 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KindTile(row.isBranch)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        nodeLabel(node.path, stringResource(R.string.mqtt_empty_level), stringResource(R.string.mqtt_leading_slash)),
                        fontSize = 15.sp,
                        color = if (node.name.isEmpty()) colors.muted else colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.pinned) Icon(PinGlyph, contentDescription = stringResource(R.string.mqtt_pinned_mark), tint = colors.muted, modifier = Modifier.size(14.dp))
                }
                value?.let { ValueText(it) }
                // A value from before the session is no change, also when it arrives while the level is shown.
                val changedAt = value?.changedAt
                Meta(node, value, change, now, highlight = changedAt != null && changedAt > shownSince && now - changedAt < HIGHLIGHT_MS)
            }
            if (row.isBranch) {
                IconButton(onClick = onCard) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mqtt_branch_card), tint = colors.muted)
                }
            }
        }
    }
}

@Composable
internal fun KindTile(branch: Boolean) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(40.dp)
            .background(if (branch) colors.accentContainer else colors.tile, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (branch) FolderGlyph else FileGlyph,
            contentDescription = stringResource(if (branch) R.string.mqtt_kind_branch else R.string.mqtt_kind_topic),
            tint = if (branch) colors.onAccentContainer else colors.muted,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The value in one line: text made safe, hex when not UTF-8, or what an empty one means. */
@Composable
internal fun ValueText(value: TopicValue) {
    val colors = AppTheme.colors
    val (text, plain) = when {
        value.cleared -> stringResource(R.string.mqtt_cleared) to false
        value.isEmptyValue -> stringResource(R.string.mqtt_empty_value) to false
        else -> when (val line = remember(value) { valueLine(value.payload) }) {
            is ValueLine.Text -> line.text to true
            is ValueLine.Hex -> line.text to true
        }
    }
    Text(
        text,
        fontSize = 13.sp,
        fontFamily = if (plain) FontFamily.Monospace else FontFamily.Default,
        color = if (plain) colors.ink else colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "12 s ago · retained · 34 topics"; "before connecting" when nothing changed since the session saw it. */
@Composable
private fun Meta(node: TopicNode, value: TopicValue?, change: LastChange?, now: Long, highlight: Boolean) {
    val colors = AppTheme.colors
    val parts = buildList {
        when (change) {
            is LastChange.At -> add(ageText(relativeAge(Duration.ofMillis(maxOf(0, now - change.ms)))))
            LastChange.BeforeSession -> add(stringResource(R.string.mqtt_before_session))
            null -> Unit
        }
        if (value?.retained == true) add(stringResource(R.string.mqtt_retained))
        if (node.topicsBelow > 0) add(pluralStringResource(R.plurals.mqtt_topics, node.topicsBelow, count(node.topicsBelow)))
    }
    if (parts.isEmpty()) return
    val ageColor by animateColorAsState(if (highlight) colors.accent else colors.muted, label = "age")
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        parts.forEachIndexed { i, part ->
            Text(
                if (i == 0) part else " · $part",
                fontSize = 12.sp,
                // Only the age shows a change.
                color = if (i == 0 && change is LastChange.At) ageColor else colors.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The topics under the level whose path holds the query, or that a
 * pattern matches, as paths relative to where it looked; a tap shows the
 * topic in its level. A pattern gets its syntax line on top (or why it
 * cannot be searched) and, under the hits, subscribing its filter.
 */
@Composable
private fun SearchHits(
    state: MqttUiState,
    query: String,
    padding: PaddingValues,
    onOpen: (SearchHit) -> Unit,
    onSearchWhole: () -> Unit,
    onSubscribe: (String) -> Unit,
    onUnsubscribe: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val empty = stringResource(R.string.mqtt_empty_level)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)) {
        if (state.searchPattern) {
            item(key = "syntax") {
                val problem = state.searchProblem
                Text(
                    when (problem) {
                        null -> stringResource(R.string.mqtt_pattern_syntax)
                        PatternProblem.WILDCARD_IN_LEVEL -> stringResource(R.string.mqtt_pattern_in_level)
                        PatternProblem.HASH_NOT_LAST -> stringResource(R.string.mqtt_pattern_hash_last)
                    },
                    color = if (problem == null) colors.muted else colors.error,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // A pattern that cannot run has nothing more to show.
            if (state.searchProblem != null) return@LazyColumn
        }
        if (state.hits.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(if (state.searchPattern) R.string.mqtt_no_pattern_match else R.string.mqtt_no_match, query),
                    color = colors.muted,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // Patterns are exact, as the broker is: say when the case alone kept them from matching.
            if (state.hitsIgnoringCase > 0) {
                item(key = "case") {
                    Text(
                        stringResource(R.string.mqtt_pattern_case, count(state.hitsIgnoringCase)),
                        color = colors.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                    )
                }
            }
            // Nothing in this branch: one tap widens the search, so an empty result is no dead end.
            if (!state.searchAt.isRoot) {
                item(key = "whole") {
                    SecondaryButton(
                        stringResource(R.string.mqtt_search_whole),
                        SearchGlyph,
                        enabled = true,
                        Modifier.padding(horizontal = 20.dp),
                        onClick = onSearchWhole,
                    )
                }
            }
        }
        if (state.hitsTotal > state.hits.size) {
            item(key = "more") {
                Text(
                    stringResource(R.string.mqtt_hits_more, count(state.hits.size), count(state.hitsTotal)),
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(state.hits, key = { rowKey(it.relative) }) { hit ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.mqtt_open_card)) { onOpen(hit) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    hitLabel(hit.relative, empty),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.node.value?.let { ValueText(it) }
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 20.dp))
        }
        state.filterOffer?.let { offer -> item(key = "subscribe") { SubscribeOffer(offer, onSubscribe, onUnsubscribe) } }
    }
}

/**
 * Under a pattern's hits: the absolute filter it makes and "Subscribe to
 * this filter", "Unsubscribe from this filter" when it is a pin itself,
 * or the button off with why.
 */
@Composable
private fun SubscribeOffer(offer: FilterOffer, onSubscribe: (String) -> Unit, onUnsubscribe: (String) -> Unit) {
    val colors = AppTheme.colors
    val filter = when (offer) {
        is FilterOffer.Subscribe -> offer.filter
        is FilterOffer.Unsubscribe -> offer.filter
        is FilterOffer.Covered -> offer.filter
        else -> null
    }
    val note = when (offer) {
        is FilterOffer.Subscribe, is FilterOffer.Unsubscribe -> null
        is FilterOffer.Covered -> stringResource(R.string.mqtt_filter_pinned, offer.by)
        FilterOffer.SessionClosed -> stringResource(R.string.mqtt_closed)
        FilterOffer.NotForBroker -> stringResource(R.string.mqtt_filter_no_globs)
        FilterOffer.TooLong -> stringResource(R.string.mqtt_topic_too_long, MAX_TOPIC_BYTES)
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        filter?.let { Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        val unsubscribe = offer is FilterOffer.Unsubscribe
        SecondaryButton(
            stringResource(if (unsubscribe) R.string.mqtt_unsubscribe_filter else R.string.mqtt_subscribe_filter),
            PinGlyph,
            enabled = offer is FilterOffer.Subscribe || unsubscribe,
            // Why it is off is read with the button itself; the line below is for the eye only.
            Modifier
                .fillMaxWidth()
                .semantics { note?.let { stateDescription = it } },
        ) { filter?.let(if (unsubscribe) onUnsubscribe else onSubscribe) }
        note?.let { Text(it, fontSize = 12.sp, color = colors.muted, modifier = Modifier.clearAndSetSemantics {}) }
    }
}

/**
 * A topic typed or pasted, taken as is (`/devices/x` and `devices/x` are
 * different places). [onGo] answers why it cannot be used, or null, and
 * then the dialog is closed by its caller; a problem stays under the field
 * until the text changes.
 */
@Composable
private fun TopicDialog(title: String, initial: String, confirm: String, onGo: (String) -> TopicProblem?, onDismiss: () -> Unit) {
    // The prefilled path selected whole: a paste or the first key replaces it, an arrow keeps it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val text = field.text
    var problem by remember { mutableStateOf<TopicProblem?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val go = { if (text.isNotEmpty()) problem = onGo(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Field(
                label = stringResource(R.string.mqtt_topic),
                value = field,
                onValueChange = {
                    if (it.text != field.text) problem = null
                    field = it
                },
                mono = true,
                keyboard = KeyboardType.Uri,
                focusRequester = focus,
                problem = problem?.let { topicProblemText(it) },
                // The keyboard's action key is the dialog's own button.
                onDone = go,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty(), onClick = go) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A list key a Bundle can hold, distinct for every path (`/` alone is two empty levels). */
private fun rowKey(path: MqttPath): String = "${path.levels.size}:" + path.levels.joinToString("\u0000")

@Composable
internal fun topicProblemText(problem: TopicProblem): String = when (problem) {
    TopicProblem.EMPTY -> stringResource(R.string.mqtt_topic_empty)
    TopicProblem.WILDCARD -> stringResource(R.string.mqtt_topic_wildcard)
    TopicProblem.NUL -> stringResource(R.string.mqtt_topic_nul)
    TopicProblem.TOO_LONG -> stringResource(R.string.mqtt_topic_too_long, MAX_TOPIC_BYTES)
}

internal fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)
