package ru.adsrv.frpjump.ui.mqtt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.mqtt.plugins.ConnectionPlugins
import ru.adsrv.frpjump.mqtt.plugins.Preset
import ru.adsrv.frpjump.mqtt.plugins.RuleCheck
import ru.adsrv.frpjump.mqtt.plugins.RuleField
import ru.adsrv.frpjump.mqtt.plugins.RuleProblem
import ru.adsrv.frpjump.mqtt.plugins.RuleSpec
import ru.adsrv.frpjump.mqtt.plugins.STATUS_COMMAND_PLUGIN
import ru.adsrv.frpjump.mqtt.plugins.StatusCommandSettings
import ru.adsrv.frpjump.mqtt.plugins.TemplateProblem
import ru.adsrv.frpjump.mqtt.plugins.checkRule
import ru.adsrv.frpjump.mqtt.plugins.moveRule
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.MainButton
import ru.adsrv.frpjump.ui.PlusGlyph
import ru.adsrv.frpjump.ui.files.SecondaryButton
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.ui.userMessage
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The MQTT plugins of one connection (its settings, also reached from the
 * explorer's "⋮"): each plugin with its switch and, when on, its settings.
 * "Wiren Board" is a switch only; "Status and command topics" has the
 * presets as switches, the own rules as a list with + (add), drag
 * (reorder), a tap (change) and delete with Undo. Every change is saved at once.
 */
@Composable
fun PluginsSheet(profile: String, connectionId: String, subtitle: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val store = app.mqttPlugins
    val changes by store.changes.collectAsStateWithLifecycle()
    val hidden by app.pendingUndo.hidden.collectAsStateWithLifecycle()
    val all by produceState<ConnectionPlugins?>(null, changes) {
        value = withContext(Dispatchers.IO) { store.get(profile, connectionId) }
    }
    // A change written in the app's scope: closing the sheet right after the tap must not lose it.
    val changeAll = { edit: (ConnectionPlugins) -> ConnectionPlugins ->
        app.appScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { store.update(profile, connectionId, edit) }.exceptionOrNull()
            }
            if (failure != null) app.notices.show(Texts.get(R.string.settings_save_failed, failure.userMessage()), NoticeKind.WARN, long = true)
        }
        Unit
    }
    val change = { edit: (StatusCommandSettings) -> StatusCommandSettings -> changeAll { it.copy(statusCommand = edit(it.statusCommand)) } }
    // The rule being changed (its id), or "" for a new one.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    AppSheet(title = stringResource(R.string.plugins_title), subtitle = subtitle, onDismiss = onDismiss) {
        val plugins = all ?: return@AppSheet
        val s = plugins.statusCommand
        // A device view of the Wiren Board convention: on or off, nothing more to set.
        SwitchRow(stringResource(R.string.plugin_wirenboard), stringResource(R.string.plugin_wirenboard_hint), plugins.wirenBoard.on, bold = true) { on ->
            changeAll { it.copy(wirenBoard = it.wirenBoard.copy(on = on)) }
            // Off: its session drops the plugin's filters at once, also when no explorer screen is open.
            if (!on) app.mqtt.find(profile, connectionId)?.setPluginFilters(emptyList())
        }
        SwitchRow(stringResource(R.string.plugin_status_command), stringResource(R.string.plugin_status_command_hint), s.on, bold = true) { on ->
            change { it.copy(on = on) }
        }
        if (!s.on) return@AppSheet
        SectionLabel(stringResource(R.string.plugin_presets))
        STATUS_COMMAND_PLUGIN.presets.forEach { preset ->
            SwitchRow(preset.name, presetSummary(preset), preset.id in s.presets, mono = true) { on ->
                change { it.copy(presets = if (on) it.presets + preset.id else it.presets - preset.id) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(stringResource(R.string.plugin_own_rules))
            Text(stringResource(R.string.plugin_own_rules_hint), fontSize = 13.sp, lineHeight = 18.sp, color = AppTheme.colors.muted)
        }
        val gone = PendingUndo.mqttRules(hidden, profile, connectionId)
        val shown = s.rules.filter { it.id !in gone }
        if (shown.isEmpty()) {
            Text(stringResource(R.string.plugin_own_rules_none), fontSize = 14.sp, color = AppTheme.colors.muted)
        } else {
            OwnRules(
                shown,
                onMove = { id, by -> change { it.copy(rules = moveRule(it.rules, shown.map(RuleSpec::id), id, by)) } },
                onEdit = { editing = it.id },
                onDelete = { rule ->
                    app.pendingUndo.enqueue(PendingUndo.mqttRuleKey(profile, connectionId, rule.id), commit = {
                        withContext(Dispatchers.IO) {
                            store.update(profile, connectionId) { p -> p.copy(statusCommand = p.statusCommand.copy(rules = p.statusCommand.rules.filter { it.id != rule.id })) }
                        }
                    })
                },
            )
        }
        SecondaryButton(stringResource(R.string.plugin_rule_add), PlusGlyph, enabled = true, Modifier.fillMaxWidth()) { editing = "" }
        editing?.let { id ->
            val rule = s.rules.firstOrNull { it.id == id }
            RuleSheet(
                initial = rule,
                onSave = { spec ->
                    val saved = spec.copy(id = rule?.id ?: UUID.randomUUID().toString())
                    change { c -> c.copy(rules = if (rule == null) c.rules + saved else c.rules.map { if (it.id == saved.id) saved else it }) }
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/** A preset's rules in short: the first one's templates, "+N" for the rest. */
private fun presetSummary(preset: Preset): String {
    val first = preset.rules.first()
    val text = first.command?.let { "${first.status} → $it" } ?: first.status
    return if (preset.rules.size > 1) "$text +${preset.rules.size - 1}" else text
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.muted, modifier = Modifier.padding(top = 4.dp))

/** A title, a line under it and a switch; the whole row toggles, as the publish sheet's Retain. */
@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, bold: Boolean = false, mono: Boolean = false, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = if (bold) 16.sp else 15.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium)
            Text(hint, fontSize = if (mono) 12.sp else 13.sp, color = colors.muted, fontFamily = if (mono) FontFamily.Monospace else null)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * The own rules in order: a handle to drag a rule up or down (TalkBack:
 * "Move up" / "Move down"), a tap to change it, the bin to delete it.
 * Dragging moves the row under the finger; the order is saved when it is let go.
 */
@Composable
private fun OwnRules(rules: List<RuleSpec>, onMove: (id: String, by: Int) -> Unit, onEdit: (RuleSpec) -> Unit, onDelete: (RuleSpec) -> Unit) {
    // The drag's callbacks outlive a recomposition: they read the list and the move as they are now.
    val current by rememberUpdatedState(rules)
    val move by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val from = rules.indexOfFirst { it.id == dragging }
    // Where the dragged row would land now, and so how the others make room.
    val by = if (from < 0) 0 else (dragY / rowHeight).roundToInt().coerceIn(-from, rules.size - 1 - from)
    Column {
        rules.forEachIndexed { i, rule ->
            val shift = when {
                from < 0 || i == from -> 0
                by > 0 && i in from + 1..from + by -> -rowHeight
                by < 0 && i in from + by until from -> rowHeight
                else -> 0
            }
            val y = if (i == from) dragY.roundToInt() else shift
            RuleRow(
                rule,
                lifted = i == from,
                canUp = i > 0,
                canDown = i < rules.size - 1,
                modifier = Modifier
                    .offset { IntOffset(0, y) }
                    .onSizeChanged { rowHeight = maxOf(1, it.height) },
                handle = Modifier.pointerInput(rule.id) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = rule.id
                            dragY = 0f
                        },
                        onDragEnd = {
                            val at = current.indexOfFirst { it.id == rule.id }
                            val moved = if (at < 0) 0 else (dragY / rowHeight).roundToInt().coerceIn(-at, current.size - 1 - at)
                            dragging = null
                            dragY = 0f
                            if (moved != 0) move(rule.id, moved)
                        },
                        onDragCancel = {
                            dragging = null
                            dragY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragY += amount.y
                        },
                    )
                },
                onMove = { onMove(rule.id, it) },
                onEdit = { onEdit(rule) },
                onDelete = { onDelete(rule) },
            )
        }
    }
}

@Composable
private fun RuleRow(
    rule: RuleSpec,
    lifted: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    modifier: Modifier,
    handle: Modifier,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    val templates = rule.command?.let { "${rule.status} → $it" } ?: rule.status
    val name = rule.name?.takeIf { it.isNotBlank() }
    val up = stringResource(R.string.plugin_rule_move_up)
    val down = stringResource(R.string.plugin_rule_move_down)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (lifted) Modifier.shadow(4.dp, RoundedCornerShape(12.dp)) else Modifier)
            .background(if (lifted) colors.tile else colors.card, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.plugin_rule_edit), onClick = onEdit)
            .semantics {
                customActions = buildList {
                    if (canUp) add(CustomAccessibilityAction(up) { onMove(-1); true })
                    if (canDown) add(CustomAccessibilityAction(down) { onMove(1); true })
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.plugin_rule_drag), tint = colors.muted, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            name?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            Text(templates, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (name == null) colors.ink else colors.muted)
            if (rule.except.isNotEmpty()) {
                Text(rule.except.joinToString(", ", prefix = "− "), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.muted)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.plugin_rule_delete, name ?: templates), tint = colors.muted)
        }
    }
}

/**
 * + or a tap on a rule: its status template, command template, exclusions
 * (one per line) and name. Save checks it as the matching does, the
 * problem under the field that failed; a rule that passes is saved and
 * the sheet closes.
 */
@Composable
private fun RuleSheet(initial: RuleSpec?, onSave: (RuleSpec) -> Unit, onDismiss: () -> Unit) {
    var status by rememberSaveable { mutableStateOf(initial?.status.orEmpty()) }
    var command by rememberSaveable { mutableStateOf(initial?.command.orEmpty()) }
    var except by rememberSaveable { mutableStateOf(initial?.except.orEmpty().joinToString("\n")) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    // Problems show once Save was tapped, then follow the typing.
    var tried by rememberSaveable { mutableStateOf(false) }
    val spec = RuleSpec(
        status = status.trim(),
        command = command.trim().ifEmpty { null },
        except = except.lines().map { it.trim() }.filter { it.isNotEmpty() },
        name = name.trim().ifEmpty { null },
    )
    val check = remember(spec) { checkRule(spec) }
    val problem = (check as? RuleCheck.Bad)?.problem?.takeIf { tried }
    AppSheet(title = stringResource(if (initial == null) R.string.plugin_rule_new else R.string.plugin_rule_change), onDismiss = onDismiss) {
        Text(stringResource(R.string.plugin_rule_syntax), fontSize = 13.sp, lineHeight = 18.sp, color = AppTheme.colors.muted)
        Field(
            label = stringResource(R.string.plugin_rule_status),
            value = status,
            onValueChange = { status = it },
            mono = true,
            keyboard = KeyboardType.Uri,
            placeholder = "home/{x...}/state",
            problem = problem?.takeIf { it.field == RuleField.STATUS }?.let { templateProblemText(it.problem) },
        )
        Field(
            label = stringResource(R.string.plugin_rule_command),
            value = command,
            onValueChange = { command = it },
            mono = true,
            keyboard = KeyboardType.Uri,
            placeholder = "home/{x...}/set",
            problem = problem?.takeIf { it.field == RuleField.COMMAND }?.let { templateProblemText(it.problem) },
            note = stringResource(R.string.plugin_rule_command_note),
        )
        Field(
            label = stringResource(R.string.plugin_rule_except),
            value = except,
            onValueChange = { except = it },
            mono = true,
            keyboard = KeyboardType.Uri,
            singleLine = false,
            minLines = 2,
            problem = problem?.takeIf { it.field == RuleField.EXCEPT }?.let { exceptProblemText(it) },
        )
        Field(label = stringResource(R.string.plugin_rule_name), value = name, onValueChange = { name = it.take(60) })
        MainButton(text = stringResource(R.string.save), busy = false, onClick = {
            tried = true
            if (check is RuleCheck.Ok) onSave(spec)
        })
    }
}

@Composable
private fun exceptProblemText(problem: RuleProblem): String =
    stringResource(R.string.plugin_rule_except_line, problem.index + 1, templateProblemText(problem.problem))

@Composable
private fun templateProblemText(problem: TemplateProblem): String = stringResource(
    when (problem) {
        TemplateProblem.EMPTY -> R.string.template_empty
        TemplateProblem.WILDCARD -> R.string.template_wildcard
        TemplateProblem.BAD_PART -> R.string.template_bad_part
        TemplateProblem.TWO_RESTS -> R.string.template_two_rests
        TemplateProblem.NAME_TWICE -> R.string.template_name_twice
        TemplateProblem.NO_LITERAL -> R.string.template_no_literal
        TemplateProblem.UNKNOWN_NAME -> R.string.template_unknown_name
        TemplateProblem.SAME_AS_STATUS -> R.string.template_same_as_status
    },
)
