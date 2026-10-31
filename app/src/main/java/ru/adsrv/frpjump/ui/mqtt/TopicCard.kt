package ru.adsrv.frpjump.ui.mqtt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.CopiedValue
import ru.adsrv.frpjump.mqtt.JsonLine
import ru.adsrv.frpjump.mqtt.PayloadReading
import ru.adsrv.frpjump.mqtt.readPayload
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.PayloadView
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.mqtt.PinMask
import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.ValueLine
import ru.adsrv.frpjump.mqtt.copiedValue
import ru.adsrv.frpjump.mqtt.decodeUtf8
import ru.adsrv.frpjump.mqtt.hexDump
import ru.adsrv.frpjump.mqtt.levelLabel
import ru.adsrv.frpjump.mqtt.multiLineText
import ru.adsrv.frpjump.mqtt.pinFilter
import ru.adsrv.frpjump.mqtt.valueLine
import ru.adsrv.frpjump.mqtt.visibleJsonLines
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ssh.copyPreview
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.CopyGlyph
import ru.adsrv.frpjump.ui.NoticeLayer
import ru.adsrv.frpjump.ui.Segmented
import ru.adsrv.frpjump.ui.files.InfoLine
import ru.adsrv.frpjump.ui.files.SecondaryButton
import ru.adsrv.frpjump.ui.files.fullTime
import ru.adsrv.frpjump.ui.notice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Characters of a text value the card draws at most; Copy value takes more. */
private const val TEXT_SHOWN = 16_000

/** Bytes of the hex view the card draws at most. */
private const val HEX_SHOWN = 4_096

/** Lines of the JSON view drawn at most (folding brings the rest into reach); each is a composable in the sheet's column. */
private const val JSON_LINES_SHOWN = 500

/** Levels of JSON indented at most; deeper lines stay at this indent so they keep a usable width. */
private const val JSON_INDENT_LEVELS = 8

/** Characters of copied text the notice shows, as the terminal's. */
private const val COPY_PREVIEW = 40

/**
 * The card of a topic or a branch, a bottom sheet as the file card: the
 * full path; a value in full with its view (Text / JSON / Hex, detected,
 * switchable for this card), size, retained, QoS and time; a branch's
 * count of topics and its newest change; the last values; Copy path, Copy
 * value, Publish…, Pin… and Unpin. [canPin] is false once the session has
 * ended; Publish… is then off too. [state] is the session's, for Send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicCard(
    card: CardState,
    canPin: Boolean,
    state: MqttState,
    onPin: (PinMask) -> String,
    onUnpin: (PinMask) -> Unit,
    onPublish: (topic: String, payload: String, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val value = card.node?.value
    val path = card.path
    var picking by rememberSaveable { mutableStateOf(false) }
    var publishing by rememberSaveable { mutableStateOf(false) }
    // Chosen by hand for this card; null follows what each new value is detected as.
    var chosen by rememberSaveable(path.format(), path.levels.size) { mutableStateOf<PayloadView?>(null) }
    val payload = value?.payload?.takeIf { it.isNotEmpty() }
    // Read once per value, off the main thread: up to 64 KB of JSON is parsed and laid out in lines.
    val reading by produceState<PayloadReading?>(null, payload) {
        this.value = payload?.let { withContext(Dispatchers.Default) { readPayload(it) } }
    }
    val json = reading?.json
    val view = chosen?.takeIf { it != PayloadView.JSON || json != null } ?: reading?.view
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        NoticeLayer {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    KindTile(card.isBranch)
                    Text(
                        levelLabel(path.name, stringResource(R.string.mqtt_empty_level)),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SelectionContainer { InfoLine(stringResource(R.string.mqtt_card_path), path.format(), mono = true) }
                    val node = card.node
                    if (card.isBranch) {
                        InfoLine(
                            stringResource(R.string.mqtt_card_below),
                            pluralStringResource(R.plurals.mqtt_topics, node?.topicsBelow ?: 0, count(node?.topicsBelow ?: 0)),
                        )
                        node?.newestChangeAt?.takeIf { it > 0 }?.let {
                            InfoLine(stringResource(R.string.mqtt_card_newest), fullTime(Instant.ofEpochMilli(it)))
                        }
                    }
                    if (node == null && !card.isBranch) InfoLine(stringResource(R.string.mqtt_card_value), stringResource(R.string.mqtt_no_messages))
                    if (card.pinned.isNotEmpty()) Pinned(path, card.pinned, canPin, onUnpin)
                }
                value?.let { ValueBlock(it, view, json, onView = { v -> chosen = v }, detectedJson = json != null) }
                if (card.history.isNotEmpty()) History(card.history)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.mqtt_copy_path), CopyGlyph, enabled = true, Modifier.weight(1f)) {
                            copy(context, path.format().let { CopiedValue(it, cut = false) })
                        }
                        if (value != null) {
                            SecondaryButton(stringResource(R.string.mqtt_copy_value), CopyGlyph, enabled = payload != null && view != null, Modifier.weight(1f)) {
                                if (payload != null && view != null) copy(context, copiedValue(payload, view))
                            }
                        }
                    }
                    // Full width, one per row: the labels stay whole at any phone width and font size.
                    SecondaryButton(stringResource(R.string.mqtt_publish_ellipsis), null, enabled = canPin, Modifier.fillMaxWidth()) { publishing = true }
                    if (card.pinned.size < PinMask.entries.size) {
                        SecondaryButton(stringResource(R.string.mqtt_pin_ellipsis), null, enabled = canPin, Modifier.fillMaxWidth()) { picking = true }
                    }
                }
            }
        }
    }
    if (publishing) {
        PublishSheet(path = path.format(), state = state, onPublish = onPublish, onDismiss = { publishing = false })
    }
    if (picking) {
        PinSheet(
            path = path,
            pinned = card.pinned,
            initial = if (card.isBranch && value == null) PinMask.WHOLE_BRANCH else PinMask.TOPIC,
            onPick = { mask ->
                val filter = onPin(mask)
                context.notice(context.getString(R.string.mqtt_pinned_notice, filter), NoticeKind.DONE)
            },
            onDismiss = { picking = false },
        )
    }
}

/** The pins made on this path, each with Unpin, done at once with a notice. */
@Composable
private fun Pinned(path: MqttPath, masks: List<PinMask>, canPin: Boolean, onUnpin: (PinMask) -> Unit) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.mqtt_card_pinned), fontSize = 12.sp, color = colors.muted)
        masks.forEach { mask ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pinFilter(path, mask), fontSize = 14.sp, color = colors.ink, fontFamily = FontFamily.Monospace)
                    Text(maskLabel(mask), fontSize = 12.sp, color = colors.muted)
                }
                // Gone at once with Undo on the shared notice (UI rules); unsubscribed when that goes.
                val label = stringResource(R.string.mqtt_unpin_filter, pinFilter(path, mask))
                TextButton(
                    enabled = canPin,
                    onClick = { onUnpin(mask) },
                    modifier = Modifier.semantics { contentDescription = label },
                ) { Text(stringResource(R.string.mqtt_unpin), color = if (canPin) colors.accent else colors.muted) }
            }
        }
    }
}

/** The value in its view, with the view switch and size · retained · QoS · time. */
@Composable
private fun ValueBlock(value: TopicValue, view: PayloadView?, json: List<JsonLine>?, onView: (PayloadView) -> Unit, detectedJson: Boolean) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mqtt_card_value), fontSize = 12.sp, color = colors.muted)
        if (view != null) {
            val options = if (detectedJson) PayloadView.entries.toList() else listOf(PayloadView.TEXT, PayloadView.HEX)
            Segmented(options, view, label = { viewLabel(it) }, onPick = onView)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.tile, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            when {
                value.cleared -> Text(stringResource(R.string.mqtt_cleared), fontSize = 14.sp, color = colors.muted)
                value.isEmptyValue -> Text(stringResource(R.string.mqtt_empty_value), fontSize = 14.sp, color = colors.muted)
                view == PayloadView.JSON && json != null -> JsonView(json)
                view == PayloadView.HEX -> HexView(value.payload)
                else -> TextView(value.payload)
            }
        }
        Text(
            listOfNotNull(
                pluralStringResource(R.plurals.mqtt_bytes, value.payload.size, count(value.payload.size)),
                stringResource(R.string.mqtt_retained).takeIf { value.retained },
                stringResource(R.string.mqtt_qos, value.qos),
                fullTime(Instant.ofEpochMilli(value.receivedAt)),
            ).joinToString(" · "),
            fontSize = 12.sp,
            color = colors.muted,
        )
    }
}

@Composable
private fun TextView(payload: ByteArray) {
    val colors = AppTheme.colors
    val text = remember(payload) {
        val decoded = decodeUtf8(payload) ?: String(payload, Charsets.UTF_8)
        multiLineText(decoded, TEXT_SHOWN) to (decoded.length > TEXT_SHOWN)
    }
    SelectionContainer { Text(text.first, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.ink) }
    if (text.second) CutNote()
}

@Composable
private fun HexView(payload: ByteArray) {
    val colors = AppTheme.colors
    val text = remember(payload) { hexDump(payload, maxBytes = HEX_SHOWN).joinToString("\n") }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        SelectionContainer { Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.ink, softWrap = false) }
    }
    if (payload.size > HEX_SHOWN) CutNote()
}

/** Pretty JSON; a tap on an opening line folds what it holds, a tap again unfolds it. */
@Composable
private fun JsonView(lines: List<JsonLine>) {
    val colors = AppTheme.colors
    // Folds belong to these lines: a value with another shape starts unfolded (equal lines keep them).
    var folded by remember(lines) { mutableStateOf(emptySet<Int>()) }
    val visible = visibleJsonLines(lines, folded)
    Column {
        visible.take(JSON_LINES_SHOWN).forEach { (index, isFolded) ->
            val line = lines[index]
            val text = remember(line, isFolded) {
                val shown = if (isFolded) line.text + " … " + lines[line.end].text else line.text
                multiLineText(shown, TEXT_SHOWN)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (line.foldable) {
                            Modifier.clickable(role = Role.Button, onClickLabel = stringResource(if (isFolded) R.string.mqtt_unfold else R.string.mqtt_fold)) {
                                folded = if (isFolded) folded - index else folded + index
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = (minOf(line.depth, JSON_INDENT_LEVELS) * 12).dp),
            ) {
                // A fixed column for the fold mark, so marked and plain lines indent alike.
                Box(Modifier.width(16.dp)) {
                    if (line.foldable) Text(if (isFolded) "▸" else "▾", fontSize = 13.sp, color = colors.muted)
                }
                Text(text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.ink, modifier = Modifier.weight(1f))
                if (isFolded) Text(" ${count(line.children)}", fontSize = 12.sp, color = colors.muted)
            }
        }
    }
    if (visible.size > JSON_LINES_SHOWN) CutNote()
}

@Composable
private fun CutNote() {
    Text(stringResource(R.string.mqtt_value_cut), fontSize = 12.sp, color = AppTheme.colors.muted, modifier = Modifier.padding(top = 6.dp))
}

/** The last values, newest first: time with seconds, the value in one line. */
@Composable
private fun History(history: List<TopicValue>) {
    val colors = AppTheme.colors
    val formats = rememberClockFormats()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.mqtt_card_history), fontSize = 12.sp, color = colors.muted)
        history.forEach { value ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(clockTime(value.receivedAt, formats), fontSize = 13.sp, color = colors.muted, maxLines = 1)
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
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** "Pin…": one tap on a mask pins it and closes; masks already pinned are marked and not offered again. */
@Composable
private fun PinSheet(path: MqttPath, pinned: List<PinMask>, initial: PinMask, onPick: (PinMask) -> Unit, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(title = stringResource(R.string.mqtt_pin_title), subtitle = stringResource(R.string.mqtt_pin_subtitle), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PinMask.entries.forEach { mask ->
                val done = mask in pinned
                val suggested = mask == initial && !done
                val pinnedState = stringResource(R.string.mqtt_pinned_mark)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(if (suggested) colors.accentContainer else colors.card, RoundedCornerShape(12.dp))
                        .clickable(enabled = !done, role = Role.Button) {
                            onPick(mask)
                            onDismiss()
                        }
                        .semantics { if (done) stateDescription = pinnedState }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            maskLabel(mask),
                            fontSize = 16.sp,
                            fontWeight = if (suggested) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                done -> colors.muted
                                suggested -> colors.onAccentContainer
                                else -> colors.ink
                            },
                        )
                        Text(
                            pinFilter(path, mask),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (done) {
                        Text(stringResource(R.string.mqtt_pinned_mark), fontSize = 12.sp, color = colors.muted)
                        Icon(Icons.Filled.Check, contentDescription = null, tint = colors.muted, modifier = Modifier.padding(start = 6.dp).width(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun maskLabel(mask: PinMask): String = when (mask) {
    PinMask.TOPIC -> stringResource(R.string.mqtt_mask_topic)
    PinMask.ONE_LEVEL_BELOW -> stringResource(R.string.mqtt_mask_level)
    PinMask.WHOLE_BRANCH -> stringResource(R.string.mqtt_mask_branch)
}

@Composable
private fun viewLabel(view: PayloadView): String = when (view) {
    PayloadView.TEXT -> stringResource(R.string.mqtt_view_text)
    PayloadView.JSON -> stringResource(R.string.mqtt_view_json)
    PayloadView.HEX -> stringResource(R.string.mqtt_view_hex)
}

/** Puts [copied] on the clipboard and says so on top, with a cleaned preview as the terminal does. */
private fun copy(context: Context, copied: CopiedValue) {
    try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.kind_mqtt), copied.text))
    } catch (_: RuntimeException) {
        context.notice(context.getString(R.string.mqtt_copy_failed), NoticeKind.WARN)
        return
    }
    val preview = copyPreview(copied.text, COPY_PREVIEW)
    val text = if (copied.cut) context.getString(R.string.mqtt_copied_cut, preview) else context.getString(R.string.terminal_copied, preview)
    context.notice(text, NoticeKind.DONE, key = "copied")
}

/** Formatters of a value's time with seconds: today's (time only) and older ones (with the date); built once per locale. */
private class ClockFormats(val today: DateTimeFormatter, val older: DateTimeFormatter)

@Composable
private fun rememberClockFormats(): ClockFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val h24 = DateFormat.is24HourFormat(context)
    return remember(locale, h24) {
        val time = if (h24) "Hms" else "hms"
        fun of(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        ClockFormats(of(time), of("dMMM$time"))
    }
}

/** A value's time with seconds: the time today, else also the date. */
private fun clockTime(ms: Long, formats: ClockFormats): String {
    val local = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return (if (local.toLocalDate() == LocalDate.now(local.zone)) formats.today else formats.older).format(local)
}
