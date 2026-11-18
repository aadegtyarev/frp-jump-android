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
import androidx.compose.ui.semantics.Role
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
 * One connection, the same on the device card and the Tunnels tab, compact: on the left its name (with the device where the view has no device header),
 * the kind as text over the port and login, the state with the local address, and the assistant mark when it has a grant; on the right, vertically
 * centred, up to three round buttons: the main action (only while up), "⏻" and "⋮". A tap on the card does its main action, a long press opens the
 * same menu as "⋮". On a device known to be [offline] ([ru.adsrv.frpjump.connections.knownOffline]), a connection that is down is dimmed and cannot
 * be connected; its "⋮" and long press work. While it is being connected the card offers only a disabled "⏻" in the "on" style (a spinner) and "⋮"; while
 * being disconnected, a busy "⏻" to stop and "⋮".
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
) {
    val colors = AppTheme.colors
    val c = row.connection
    val up = row.tunnel != null
    val busy = row.pending != null
    // Up, it stays usable: "⏻ off" is what the owner needs then.
    val unreachable = connectBlocked(row, offline)
    val tapEnabled = cardTap(row) != CardTap.NONE && !unreachable
    val openMenu by rememberUpdatedState { onMenu(true) }
    val details = details(row, sshMethod)
    // Its own name only when it says more than the kind: "z2m", not "SSH".
    val title = if (row.saved) connectionTitle(c, kindLabel(c.kind)) else rowName(row)
    val name = rowName(row)
    val stacked = LocalDensity.current.fontScale >= STACK_FROM
    val context = LocalContext.current
    val state = stateOf(row)
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(18.dp),
        border = if (up) BorderStroke(1.5.dp, colors.accent.copy(alpha = 0.45f)) else BorderStroke(1.dp, colors.divider),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            // Anywhere but its buttons: its main action, as the button labels it (connecting first when down);
            // being disconnected, or its device offline, no action of its own, for touch or TalkBack,
            // while a long press still opens its menu (TalkBack has "⋮" for it).
            .then(
                if (tapEnabled) {
                    Modifier.combinedClickable(
                        onClickLabel = cardTapLabel(row, sessions),
                        onClick = { actions.tap(row) },
                        onLongClick = { if (hasMenu(row)) onMenu(true) },
                    )
                } else if (hasMenu(row)) {
                    Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { openMenu() }) }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f).alpha(if (unreachable) DIMMED else 1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The name is what tells connections apart: on lines of its own, never cut.
                val where = deviceName?.let { " · $it" }.orEmpty()
                if (title != null) {
                    Text(title + where, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(details, fontSize = 13.sp, color = colors.muted)
                } else {
                    Text(details + where, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                // State and address on one line (wrapping only when the font is too large for both), in the body font with tabular digits.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (state != null) {
                        val (ink, _) = toneColors(state.second)
                        val choice = offersDirectChoice(row)
                        Text(
                            if (choice && row.tunnel?.direct != DirectMode.KEPT) "${state.first}  ›" else state.first,
                            color = ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = if (choice) {
                                val label = stringResource(R.string.direct_or_server)
                                Modifier.clickable(onClickLabel = label, role = Role.Button) { actions.directChoice(row) }.padding(vertical = 4.dp)
                            } else Modifier,
                        )
                    } else {
                        // Why it cannot be started, where it is.
                        Text(stringResource(if (unreachable) R.string.state_device_offline else R.string.state_off), fontSize = 13.sp, color = colors.muted)
                    }
                    row.tunnel?.let {
                        Text("·", fontSize = 13.sp, color = colors.muted, modifier = Modifier.clearAndSetSemantics { })
                        LocalAddress(it.localPort, modifier = Modifier, pinned = it.pinned, mono = false)
                    }
                    SessionsPill(sessions)
                }
                if ((c.kind == PortKind.SSH && up) || sessions.total > 0) {
                    LiveTerminalDiagnostics(TunnelRef(row.profile, c.device, c.port))
                }
                problemOf(row)?.let { Text(it, color = colors.error, fontSize = 13.sp) }
                if (assistant != null) {
                    AssistantMarkLine(row, name, assistant, onForgetAssistant) { text -> context.notice(text, NoticeKind.DONE) }
                }
            }
            val buttons: @Composable () -> Unit = {
                if (up && !busy) {
                    val label = mainActionLabel(row, sessions)
                    RoundButton(
                        description = stringResource(R.string.main_action_named, label, name),
                        onClick = { actions.main(row) },
                        container = colors.accentContainer,
                        modifier = Modifier.testTag("card_main"),
                    ) { size ->
                        Icon(mainGlyph(row), contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(size * GLYPH_SHARE))
                        if (c.kind == PortKind.SSH && sessions.total >= 2) CountBadge(sessions.total)
                    }
                }
                // Being connected (no tunnel yet), the power button is the disabled "on" one, not a "Stop": there is nothing to stop yet.
                val connecting = row.pending == PendingOp.CONNECTING && !up
                if (up || (busy && !connecting)) {
                    RoundButton(
                        description = stringResource(R.string.disconnect_named, name),
                        onClick = { actions.disconnect(row) },
                        enabled = !busy,
                        border = colors.error,
                        modifier = Modifier.testTag("card_off"),
                    ) { size ->
                        if (busy) CircularProgressIndicator(Modifier.size(size * GLYPH_SHARE), color = colors.error, strokeWidth = 2.dp)
                        else Icon(PowerGlyph, contentDescription = null, tint = colors.error, modifier = Modifier.size(size * GLYPH_SHARE))
                    }
                } else {
                    val inactive = unreachable || connecting
                    val on = if (inactive) colors.outline else colors.accent
                    RoundButton(
                        description = stringResource(if (row.error != null && !connecting) R.string.retry_named else R.string.connect_named, name),
                        onClick = { actions.connect(row) },
                        enabled = !inactive,
                        border = on,
                        modifier = Modifier.testTag("card_on"),
                    ) { size ->
                        if (connecting) CircularProgressIndicator(Modifier.size(size * GLYPH_SHARE), color = colors.accent, strokeWidth = 2.dp)
                        else Icon(PowerGlyph, contentDescription = null, tint = if (inactive) colors.muted else colors.accent, modifier = Modifier.size(size * GLYPH_SHARE))
                    }
                }
                if (hasMenu(row)) {
                    Box {
                        RoundButton(
                            description = stringResource(R.string.connection_actions, name),
                            onClick = { onMenu(true) },
                            modifier = Modifier.testTag("card_menu"),
                        ) { size -> Icon(Icons.Filled.MoreVert, contentDescription = null, tint = colors.muted, modifier = Modifier.size(size * GLYPH_SHARE)) }
                        ConnectionMenu(menuOpen, row, actions, onDismiss = { onMenu(false) })
                    }
                }
            }
            if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
            else Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
        }
    }
}

/** How much a connection that cannot be connected now is faded. */
private const val DIMMED = 0.5f

/** From this font scale the buttons stack vertically. */
private const val STACK_FROM = 1.5f

/** The glyph's share of the circle. */
private const val GLYPH_SHARE = 0.56f

/** The connection's own glyph for its main action. */
private fun mainGlyph(row: ConnectionRow): ImageVector =
    if (!row.saved) CopyGlyph else when (row.connection.kind) {
        PortKind.SSH -> TerminalGlyph
        PortKind.WEB -> GlobeGlyph
        PortKind.MQTT -> RssGlyph
        PortKind.TCP -> CopyGlyph
    }

/**
 * A round button of the card: the circle is 32 dp and grows with the font (to 1.5 times), the touch target is at least 44 dp (the circle plus a margin, which the count badge needs). [container] fills it
 * (the main action's tonal circle), [border] outlines it (the power button); neither: a bare glyph ("⋮"). The glyph is given the circle's size.
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
    val circle = 32.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
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
                .then(if (border != null) Modifier.border(1.5.dp, border, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { content(circle) }
    }
}

/** The open sessions of an SSH connection, on its terminal button: the digits only, the button's description names the action. */
@Composable
private fun BoxScope.CountBadge(total: Int) {
    val colors = AppTheme.colors
    Text(
        total.toString(),
        // In dp, not sp: a badge that grew with the font would cover its button.
        fontSize = with(LocalDensity.current) { 11.dp.toSp() },
        fontWeight = FontWeight.Bold,
        color = colors.onAccent,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 3.dp, y = (-3).dp)
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(colors.accent, CircleShape)
            .padding(horizontal = 3.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .clearAndSetSemantics { },
    )
}

/**
 * The assistant's mark: a calm muted pill (a thin outline, no fill), the small ✦ and "For session" or "For this connection". The whole line, 48 dp tall
 * at least, is a button that opens the sheet; TalkBack reads "Assistant: <text>. Details". The sheet goes with the grant: this is composed only while
 * [mark] exists. "Ask again" does what Forget did, closes the sheet and says so.
 */
@Composable
private fun AssistantMarkLine(row: ConnectionRow, name: String, mark: AssistantMark, onForget: () -> Unit, say: (String) -> Unit) {
    val colors = AppTheme.colors
    var sheet by remember { mutableStateOf(false) }
    val text = stringResource(if (mark.rememberedAt != null) R.string.connection_assistant_chip_connection else R.string.connection_assistant_chip_session)
    val spoken = stringResource(R.string.connection_assistant_chip_description, isolateName(name), text)
    // The whole line is the touch target (48 dp at least); the pill inside it only draws the mark.
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("connection_assistant_chip")
            .clickable(role = Role.Button) { sheet = true }
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button; onClick(null) { sheet = true; true } },
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
 * "SSH · port 22 · root · key", "Web · port 443 · HTTPS", "TCP · port 5432": the kind as text, then what the connection is. For a tunnel no connection
 * names, the kind alone (its name says the port).
 */
@Composable
private fun details(row: ConnectionRow, sshMethod: SshAuthMethod?): String {
    val c = row.connection
    if (!row.saved) return kindLabel(PortKind.TCP)
    val kind = kindLabel(c.kind)
    return when {
        c.kind == PortKind.WEB && c.https -> stringResource(R.string.card_details_https, kind, c.port)
        c.kind == PortKind.SSH && sshMethod == SshAuthMethod.PASSWORD -> stringResource(R.string.card_details_ssh_password, kind, c.port, c.sshUser)
        c.kind == PortKind.SSH -> stringResource(R.string.card_details_ssh_key, kind, c.port, c.sshUser)
        else -> stringResource(R.string.card_details, kind, c.port)
    }
}
