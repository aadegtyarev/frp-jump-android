package ru.adsrv.frpjump.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.agent.openai.validOpenAiApiKey
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.FormScreen

@Composable
internal fun AssistantProviderScreen(
    snapshot: AssistantProviderSnapshot,
    onSave: (AssistantProviderDraft, (String?) -> Unit) -> Unit,
    onModels: (AssistantProviderDraft, (List<String>?, String?) -> Unit) -> (() -> Unit),
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
    if (snapshot.problem != null) {
        BackHandler(onBack = onBack)
        FormScreen(stringResource(R.string.assistant_provider_title), stringResource(R.string.retry), false, onReload, onBack,
            error = assistantProviderError(ProviderSetupException(snapshot.problem))) { }
        return
    }
    AssistantProviderForm(snapshot, onSave, onModels, onBack)
}

@Composable
private fun AssistantProviderForm(
    snapshot: AssistantProviderSnapshot,
    onSave: (AssistantProviderDraft, (String?) -> Unit) -> Unit,
    onModels: (AssistantProviderDraft, (List<String>?, String?) -> Unit) -> (() -> Unit),
    onBack: () -> Unit,
) {
    val bound = remember { snapshot }
    val boundRevision = rememberSaveable { snapshot.revision.orEmpty() }.takeIf { it.isNotEmpty() }
    var replacementRequired by rememberSaveable { mutableStateOf(false) }
    val initial = bound.settings ?: AssistantProviderSettings()
    var root by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var effort by rememberSaveable { mutableStateOf(initial.effort.orEmpty()) }
    var context by rememberSaveable { mutableStateOf(initial.contextTokens.toString()) }
    var output by rememberSaveable { mutableStateOf(initial.outputTokens.toString()) }
    var completion by rememberSaveable { mutableStateOf(initial.completionTokenField) }
    var summaryPercent by rememberSaveable { mutableStateOf(initial.summaryPercent.toString()) }
    var summaryModel by rememberSaveable { mutableStateOf(initial.summaryModel.orEmpty()) }
    // Never save plaintext input in a Bundle/SavedState. Saved key is never rendered or prefilled.
    var key by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Any?>(null) }
    var saving by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    var cancelDiscovery by remember { mutableStateOf<(() -> Unit)?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var chooseModel by remember { mutableStateOf(false) }
    var chooseEffort by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { active = false; pending = null; cancelDiscovery?.invoke(); cancelDiscovery = null } }
    val busy = pending != null
    val stale = snapshot.revision != boundRevision
    val candidate = context.toIntOrNull()?.let { c -> output.toIntOrNull()?.let { o ->
        AssistantProviderSettings(root, model, effort.takeIf { it.isNotEmpty() }, c, o, completion,
            summaryPercent.toIntOrNull() ?: 0, summaryModel.takeIf { it.isNotBlank() })
    } }
    val normalized = candidate?.let { runCatching { it.normalized() }.getOrNull() }
    val reuse = bound.settings != null && normalized?.baseUrl == bound.settings.baseUrl
    val valid = normalized != null && (if (key.isEmpty()) reuse && !replacementRequired else validOpenAiApiKey(key)) && !stale
    fun draft(): AssistantProviderDraft? {
        val c = context.toIntOrNull() ?: return null
        val o = output.toIntOrNull() ?: return null
        val now = runCatching { AssistantProviderSettings(root, model, effort.takeIf { it.isNotEmpty() }, c, o, completion,
            summaryPercent.toIntOrNull() ?: 0, summaryModel.takeIf { it.isNotBlank() }).normalized() }.getOrNull() ?: return null
        val canReuse = bound.settings != null && now.baseUrl == bound.settings.baseUrl
        if (snapshot.revision != boundRevision || (if (key.isEmpty()) !canReuse || replacementRequired else !validOpenAiApiKey(key))) return null
        return AssistantProviderDraft(now, boundRevision, key.takeIf { it.isNotEmpty() })
    }
    fun edited() { failure = null; models = emptyList() }
    val leave = { if (!saving) { pending = null; val cancel = cancelDiscovery; cancelDiscovery = null; cancel?.invoke(); onBack() } }
    BackHandler(onBack = leave)
    FormScreen(
        title = stringResource(R.string.assistant_provider_title), action = stringResource(R.string.save), busy = busy,
        actionEnabled = valid, error = failure ?: if (stale && !saving) stringResource(R.string.assistant_provider_stale) else null,
        onBack = leave,
        onAction = {
            val captured = draft()
            if (captured != null && !busy) {
                val owner = Any(); pending = owner; saving = true; failure = null
                val complete: (String?) -> Unit = { error ->
                    if (active && pending === owner) {
                        pending = null; saving = false; failure = error
                        if (error == null) { key = ""; onBack() }
                    }
                }
                try { onSave(captured, complete) } catch (error: Exception) { complete(assistantProviderError(error)) }
            }
        },
    ) {
        Text(stringResource(R.string.assistant_provider_stage_hint), color = AppTheme.colors.muted)
        Field(stringResource(R.string.assistant_provider_root), root, { root = it.take(2049); edited() },
            Modifier.testTag("assistant_root"), enabled = !busy, keyboard = KeyboardType.Uri)
        Field(stringResource(R.string.assistant_provider_key), key, { key = it.take(16_385); replacementRequired = key.isNotEmpty(); edited() },
            Modifier.testTag("assistant_key"), enabled = !busy, keyboard = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            note = stringResource(if (reuse && !replacementRequired) R.string.assistant_provider_key_keep else R.string.assistant_provider_key_required))
        Field(stringResource(R.string.assistant_provider_model), model, { model = it.take(257); edited() },
            Modifier.testTag("assistant_model"), enabled = !busy,
            note = stringResource(R.string.assistant_provider_manual_model))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = valid && !busy, modifier = Modifier.testTag("assistant_models"), onClick = models@{
                val captured = draft() ?: return@models
                val owner = Any(); pending = owner; failure = null; models = emptyList()
                val complete: (List<String>?, String?) -> Unit = { ids, error ->
                    if (active && pending === owner) {
                        pending = null; cancelDiscovery = null; failure = error
                        models = ids.orEmpty()
                        if (error == null && models.isNotEmpty()) chooseModel = true
                    }
                }
                try {
                    val cancel = onModels(captured, complete)
                    if (active && pending === owner) cancelDiscovery = cancel else cancel()
                } catch (error: Exception) { complete(null, assistantProviderError(error)) }
            }) { Text(stringResource(R.string.assistant_provider_models)) }
            if (models.isNotEmpty()) TextButton(enabled = !busy, onClick = { chooseModel = true }) {
                Text(stringResource(R.string.assistant_provider_choose_model))
            }
        }
        Field(stringResource(R.string.assistant_provider_effort), effort, { effort = it.take(33); edited() },
            Modifier.testTag("assistant_effort"), enabled = !busy, note = stringResource(R.string.assistant_provider_effort_hint))
        TextButton(enabled = !busy, onClick = { chooseEffort = true }) { Text(stringResource(R.string.assistant_provider_effort_presets)) }
        Field(stringResource(R.string.assistant_provider_context), context, { context = it.filter(Char::isDigit).take(8); edited() },
            Modifier.testTag("assistant_context"), enabled = !busy, keyboard = KeyboardType.Number)
        Field(stringResource(R.string.assistant_summary_percent), summaryPercent, { summaryPercent = it.filter(Char::isDigit).take(4); edited() },
            Modifier.testTag("assistant_summary_percent"), enabled = !busy, keyboard = KeyboardType.Number, note = stringResource(R.string.assistant_summary_hint))
        Field(stringResource(R.string.assistant_summary_model), summaryModel, { summaryModel = it.take(257); edited() },
            Modifier.testTag("assistant_summary_model"), enabled = !busy, note = stringResource(R.string.assistant_summary_model_hint))
        Field(stringResource(R.string.assistant_provider_output), output, { output = it.filter(Char::isDigit).take(8); edited() },
            Modifier.testTag("assistant_output"), enabled = !busy, keyboard = KeyboardType.Number,
            note = stringResource(R.string.assistant_provider_window_hint))
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { completion = !completion; edited() }) {
            Checkbox(checked = completion, enabled = !busy, onCheckedChange = { completion = it; edited() })
            Text(stringResource(R.string.assistant_provider_completion_field))
        }
        if (!valid && !busy) Text(stringResource(R.string.assistant_provider_invalid), color = AppTheme.colors.muted)
    }
    if (chooseModel && !stale) ChoiceDialog(stringResource(R.string.assistant_provider_model), models,
        onPick = { model = it; chooseModel = false; failure = null }, onDismiss = { chooseModel = false })
    if (chooseEffort && !stale) {
        val defaultLabel = stringResource(R.string.assistant_provider_default_effort)
        ChoiceDialog(stringResource(R.string.assistant_provider_effort), listOf(defaultLabel, "none", "low", "medium", "high", "max"),
            onPick = { effort = if (it == defaultLabel) "" else it; chooseEffort = false; edited() }, onDismiss = { chooseEffort = false })
    }
}

@Composable
private fun ChoiceDialog(title: String, values: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(values, key = { it }) { value -> TextButton(onClick = { onPick(value) }, modifier = Modifier.fillMaxWidth()) { Text(value) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
