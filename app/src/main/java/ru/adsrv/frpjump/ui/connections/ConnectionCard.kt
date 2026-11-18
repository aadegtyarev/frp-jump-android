package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.connectBlocked
import ru.adsrv.frpjump.connections.connectionTitle
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.isolateName
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.SessionCount
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AssistantGlyph
import ru.adsrv.frpjump.ui.CopyGlyph
import ru.adsrv.frpjump.ui.GlobeGlyph
import ru.adsrv.frpjump.ui.PowerGlyph
import ru.adsrv.frpjump.ui.RssGlyph
import ru.adsrv.frpjump.ui.TerminalGlyph
import ru.adsrv.frpjump.ui.notice

/**
 * One connection, the same on the device card and the Tunnels tab, as in a VPN app: on top its name (the kind alone where it has none, with the port and
 * login muted under it; with the device where the view has no device header), the state line (state, local address, sessions), a Material switch and
 * "⋮". The switch is on while the tunnel is up or coming up: turning it on connects, off disconnects (with Undo). Below, only while the tunnel is up
 * or the assistant has a grant: the main action as a text link on the left ("Terminal ›", "Open ›", "MQTT ›", "Copy address") and the assistant's
 * mark on the right. A tap on the card does its main action, a long press opens the same menu as "⋮". On a device known to be [offline]
 * ([ru.adsrv.frpjump.connections.knownOffline]), a connection that is down is dimmed and its switch is off; its "⋮" and long press work. While it is
 * being connected or disconnected its switch is off too.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionCard(
    row: ConnectionRow,
    sshMethod: SshAuthMethod?,
    sessions: SessionCount,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    actions: RowActions,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
    /** The device's name, for a view that has no device header over its cards; null where the header says it. */
    deviceName: String? = null,
    /** What the assistant may do here without a question (a session grant and/or a remembered permission); null: nothing, no mark. */
    assistant: AssistantMark? = null,
    /** "Ask again": ends both lifetimes at once, with no confirmation (it narrows authority). */
    onForgetAssistant: () -> Unit = {},
    /** The card is swiped aside ([ru.adsrv.frpjump.ui.devices.SwipeReveal]): a tap on it puts it back instead of acting. */
    revealed: Boolean = false,
    onCloseReveal: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val c = row.connection
    val up = row.tunnel != null
    val busy = row.pending != null
    // Up, it stays usable: "⏻ off" is what the owner needs then.
    val unreachable = connectBlocked(row, offline)
    val tapEnabled = cardTap(row) != CardTap.NONE && !unreachable
    val favorite = row.saved && c.favorite
    // Swiped aside: every tap on the card, its buttons and its marks only puts it back.
    val whenOpen: (() -> Unit) -> (() -> Unit) = { act -> { if (revealed) onCloseReveal() else act() } }
    val favoriteLabel = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add)
    val askLabel = stringResource(R.string.terminal_ask_assistant)
    val openMenu by rememberUpdatedState { onMenu(true) }
    // Its own name only when it says more than the kind: "z2m", not "SSH".
    val title = if (row.saved) connectionTitle(c, kindLabel(c.kind)) else null
    val name = rowName(row)
    val context = LocalContext.current
    val state = stateOf(row)
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(18.dp),
        border = if (up) BorderStroke(1.5.dp, colors.accent.copy(alpha = 0.45f)) else BorderStroke(1.dp, colors.divider),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            // The swipes' actions, for TalkBack, which has no swipe of this kind: the same two, on the card.
            .then(
                if (row.saved) {
                    Modifier.semantics {
                        customActions = listOfNotNull(
                            actions.ask?.let { ask -> CustomAccessibilityAction(askLabel) { ask(row); true } },
                            CustomAccessibilityAction(favoriteLabel) { actions.favorite(row); true },
                        )
                    }
                } else Modifier,
            )
            // Anywhere but its buttons: its main action, as the button labels it (connecting first when down);
            // being disconnected, or its device offline, no action of its own, for touch or TalkBack,
            // while a long press still opens its menu (TalkBack has "⋮" for it).
            .then(
                if (tapEnabled || revealed) {
                    Modifier.combinedClickable(
                        onClickLabel = if (revealed) null else cardTapLabel(row, sessions),
                        onClick = { if (revealed) onCloseReveal() else actions.tap(row) },
                        onLongClick = whenOpen { if (hasMenu(row)) onMenu(true) },
                    )
                } else if (hasMenu(row)) {
                    Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { openMenu() }) }
                } else {
                    Modifier
                },
            ),
    ) {
        Column {
            val link = up && !busy
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = if (link || assistant != null) 0.dp else 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f).alpha(if (unreachable) DIMMED else 1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // The name is what tells connections apart: on lines of its own, never cut.
                    val where = deviceName?.let { " \u00B7 $it" }.orEmpty()
                    val favoriteSpoken = stringResource(R.string.favorite_spoken)
                    // A favourite has a small muted star after its name; TalkBack says "favourite", not the glyph.
                    val nameLine: @Composable (String) -> Unit = { text ->
                        Text(
                            buildAnnotatedString {
                                append(text)
                                if (favorite) withStyle(SpanStyle(color = colors.muted, fontSize = 13.sp, fontWeight = FontWeight.Normal)) { append(" \u2605") }
                                append(where)
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = if (favorite) Modifier.clearAndSetSemantics { contentDescription = "$text, $favoriteSpoken$where" } else Modifier,
                        )
                    }
                    // Its own name, with the kind in the muted details under it; with none, the kind alone is the title and the details go under it.
                    if (title != null) {
                        nameLine(title)
                        Text(details(row, sshMethod, withKind = true), fontSize = 13.sp, color = colors.muted)
                    } else if (row.saved) {
                        nameLine(kindLabel(c.kind))
                        Text(details(row, sshMethod, withKind = false), fontSize = 13.sp, color = colors.muted)
                    } else {
                        nameLine(rowName(row))
                        Text(details(row, sshMethod, withKind = true), fontSize = 13.sp, color = colors.muted)
                    }
                    // State and address on one line, in the body font with tabular digits; where both do not fit, the address goes to its own line
                    // with no dot (the dot belongs to the address, [InlineFlow] never leaves it at a line end), and the sessions pill after them.
                    InlineFlow {
                        if (state != null) {
                            val (ink, _) = toneColors(state.second)
                            val choice = offersDirectChoice(row)
                            Text(
                                // No-break spaces: the arrow stays with its word.
                                if (choice && row.tunnel?.direct != DirectMode.KEPT) "${state.first}\u00A0\u00A0\u203A" else state.first,
                                color = ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = if (choice) {
                                    val label = stringResource(R.string.direct_or_server)
                                    Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = whenOpen { actions.directChoice(row) }).padding(vertical = 4.dp)
                                } else Modifier,
                            )
                        } else {
                            // Why it cannot be started, where it is.
                            Text(stringResource(if (unreachable) R.string.state_device_offline else R.string.state_off), fontSize = 13.sp, color = colors.muted)
                        }
                        row.tunnel?.let {
                            FlowSeparator { Text("\u00B7", fontSize = 13.sp, color = colors.muted, modifier = Modifier.clearAndSetSemantics { }) }
                            LocalAddress(it.localPort, modifier = Modifier, pinned = it.pinned, mono = false)
                        }
                        SessionsPill(sessions)
                        SessionKeys(sessions)
                    }
                    problemOf(row)?.let { Text(it, color = colors.error, fontSize = 13.sp) }
                }
                // The switch, as in a VPN app: on while the tunnel is up or coming up. Off while it is being stopped or started (nothing to
                // turn yet), and on a device offline while the tunnel is down.
                val connecting = row.pending == PendingOp.CONNECTING
                val tunnelSwitch = stringResource(R.string.tunnel_switch, name)
                Switch(
                    checked = (up || connecting) && row.pending != PendingOp.DISCONNECTING,
                    onCheckedChange = { on -> if (revealed) onCloseReveal() else if (on) actions.connect(row) else actions.disconnect(row) },
                    enabled = !busy && !unreachable,
                    modifier = Modifier.testTag("card_switch").semantics { contentDescription = tunnelSwitch },
                )
                if (hasMenu(row)) {
                    Box {
                        RoundButton(
                            description = stringResource(R.string.connection_actions, name),
                            onClick = whenOpen { onMenu(true) },
                            modifier = Modifier.testTag("card_menu"),
                        ) { size -> Icon(Icons.Filled.MoreVert, contentDescription = null, tint = colors.muted, modifier = Modifier.size(size * GLYPH_SHARE)) }
                        ConnectionMenu(menuOpen, row, actions, onDismiss = { onMenu(false) })
                    }
                }
            }
            if (link || assistant != null) {
                // The main action as a link on the left, the assistant's mark on the right; at a large font they wrap, the link first.
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (link) MainActionLink(row, sessions, name, whenOpen { actions.main(row) })
                    if (assistant != null) {
                        AssistantMarkLine(row, name, assistant, onForgetAssistant, revealed, onCloseReveal) { text -> context.notice(text, NoticeKind.DONE) }
                    }
                }
            }
        }
    }
}

/** How much a connection that cannot be connected now is faded. */
private const val DIMMED = 0.5f

/** The visible diameter of a round button, before the font scale. */
private val CIRCLE = 36.dp

/** The outline of every outlined round button. */
private val OUTLINE = 1.5.dp

/** The glyph's share of the circle. */
private const val GLYPH_SHARE = 0.56f

/** The glyph before the main action's text; none for MQTT ("MQTT ›"). */
private fun mainGlyph(row: ConnectionRow): ImageVector? =
    if (!row.saved) CopyGlyph else when (row.connection.kind) {
        PortKind.SSH -> TerminalGlyph
        PortKind.WEB -> GlobeGlyph
        PortKind.MQTT -> null
        PortKind.TCP -> CopyGlyph
    }

/**
 * The main action of an up connection as an accent text link, at least 48 dp tall: "[glyph] Terminal ›", "Terminal · 2 ›", "Open ›", "MQTT ›" or,
 * for TCP and an unsaved tunnel, "Copy address" (it acts at once, no chevron). TalkBack reads the action's label with the connection's name.
 */
@Composable
private fun MainActionLink(row: ConnectionRow, sessions: SessionCount, name: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val spoken = stringResource(R.string.main_action_named, mainActionLabel(row, sessions), name)
    val copies = !row.saved || row.connection.kind == PortKind.TCP
    val label = when {
        copies -> stringResource(R.string.card_copy_address)
        row.connection.kind == PortKind.MQTT -> kindLabel(PortKind.MQTT)
        else -> mainActionLabel(row, sessions)
    }
    val glyph = mainGlyph(row)
    Row(
        Modifier
            .testTag("card_main")
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // In sp: the glyph keeps its size beside the text.
        if (glyph != null) Icon(glyph, contentDescription = null, tint = colors.accent, modifier = Modifier.size(with(LocalDensity.current) { 18.sp.toDp() }))
        // No-break space: the chevron stays with its word.
        Text(if (copies) label else "$label\u00A0\u203A", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.accent, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * A round button of the card: the circle is 36 dp and grows with the font (to 1.5 times), the touch target is at least 44 dp (the circle plus a margin, which the count badge needs). [border] outlines it
 * (the main action in the accent, the power button in its colour), the same line for every outlined button, no fill; no border: a bare glyph ("⋮"). [container] is only for a button that wants a fill. The glyph is given the circle's size.
 */
@Composable
internal fun RoundButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Color.Transparent,
    border: Color? = null,
    content: @Composable BoxScope.(Dp) -> Unit,
) {
    val circle = CIRCLE * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val target = maxOf(44.dp, circle + 8.dp)
    Box(
        modifier
            .size(target)
            // Not clipped (the count badge sits over the circle's edge): a round ripple of its own.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(bounded = false, radius = target / 2), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(circle)
                .background(container, CircleShape)
                .then(if (border != null) Modifier.border(OUTLINE, border, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { content(circle) }
    }
}

/**
 * The assistant's mark: a calm muted pill (a thin outline, no fill), the small ✦ and "For session" or "For this connection". The whole line, 48 dp tall
 * at least, is a button that opens the sheet; TalkBack reads "Assistant: <text>. Details". The sheet goes with the grant: this is composed only while
 * [mark] exists. "Ask again" does what Forget did, closes the sheet and says so.
 */
@Composable
private fun AssistantMarkLine(row: ConnectionRow, name: String, mark: AssistantMark, onForget: () -> Unit, revealed: Boolean, onCloseReveal: () -> Unit, say: (String) -> Unit) {
    val colors = AppTheme.colors
    var sheet by remember { mutableStateOf(false) }
    val text = stringResource(if (mark.rememberedAt != null) R.string.connection_assistant_chip_connection else R.string.connection_assistant_chip_session)
    val spoken = stringResource(R.string.connection_assistant_chip_description, isolateName(name), text)
    // The whole line is the touch target (48 dp at least); the pill inside it only draws the mark.
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .testTag("connection_assistant_chip")
            .clickable(role = Role.Button) { if (revealed) onCloseReveal() else sheet = true }
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button; onClick(null) { if (revealed) onCloseReveal() else sheet = true; true } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.border(1.dp, colors.outline, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(AssistantGlyph, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
            Text(text, fontSize = 13.sp, color = colors.muted)
        }
    }
    if (sheet) {
        val asksAgain = stringResource(R.string.connection_assistant_asks_again, isolateName(name))
        ConnectionAssistantSheet(
            name = name,
            mark = mark,
            onAskAgain = { sheet = false; onForget(); say(asksAgain) },
            onClose = { sheet = false },
        )
    }
}

/**
 * "SSH · port 22 · root · key", "Web · port 443 · HTTPS", "TCP · port 5432": the kind as text, then what the connection is; [withKind] false leaves
 * the kind out ("port 22 · root · key"), where the title already is the kind. For a tunnel no connection names, the kind alone (its name says the port).
 */
@Composable
private fun details(row: ConnectionRow, sshMethod: SshAuthMethod?, withKind: Boolean): String {
    val c = row.connection
    if (!row.saved) return kindLabel(PortKind.TCP)
    val kind = kindLabel(c.kind)
    return when {
        c.kind == PortKind.WEB && c.https -> if (withKind) stringResource(R.string.card_details_https, kind, c.port) else stringResource(R.string.card_port_https, c.port)
        c.kind == PortKind.SSH && sshMethod == SshAuthMethod.PASSWORD ->
            if (withKind) stringResource(R.string.card_details_ssh_password, kind, c.port, c.sshUser) else stringResource(R.string.card_port_ssh_password, c.port, c.sshUser)
        c.kind == PortKind.SSH ->
            if (withKind) stringResource(R.string.card_details_ssh_key, kind, c.port, c.sshUser) else stringResource(R.string.card_port_ssh_key, c.port, c.sshUser)
        else -> if (withKind) stringResource(R.string.card_details, kind, c.port) else stringResource(R.string.card_port, c.port)
    }
}
