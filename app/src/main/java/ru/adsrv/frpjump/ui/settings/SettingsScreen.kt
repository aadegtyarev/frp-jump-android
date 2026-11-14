package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.ui.AppAlertDialog
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.BuildConfig
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.lock.AuthResult
import ru.adsrv.frpjump.locale.AppLanguage
import ru.adsrv.frpjump.locale.currentAppLanguage
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.settings.AppLockAfter
import ru.adsrv.frpjump.settings.BackgroundPoll
import ru.adsrv.frpjump.settings.MAX_LOCAL_PORT
import ru.adsrv.frpjump.settings.MIN_LOCAL_PORT
import ru.adsrv.frpjump.settings.MIN_LOCAL_PORTS
import ru.adsrv.frpjump.settings.PortRangeProblem
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.settings.TERMINAL_HISTORY_CHOICES
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FileGlyph
import ru.adsrv.frpjump.ui.PluginGlyph
import ru.adsrv.frpjump.ui.plugins.PluginsScreen
import ru.adsrv.frpjump.ui.ChoiceSheet
import ru.adsrv.frpjump.ui.Field
import ru.adsrv.frpjump.ui.EyeOffGlyph
import ru.adsrv.frpjump.ui.FingerprintGlyph
import ru.adsrv.frpjump.ui.CopyGlyph
import ru.adsrv.frpjump.ui.KeyGlyph
import ru.adsrv.frpjump.ui.ShareGlyph
import ru.adsrv.frpjump.ssh.SshKeySlot
import ru.adsrv.frpjump.ui.ssh.keyTitle
import ru.adsrv.frpjump.ui.RepeatGlyph
import ru.adsrv.frpjump.ui.SwapGlyph
import ru.adsrv.frpjump.ui.ThemeGlyph
import androidx.compose.ui.graphics.vector.ImageVector
import ru.adsrv.frpjump.ui.MainButton
import ru.adsrv.frpjump.ui.SecretField
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.profiles.ForgetProfileDialog
import ru.adsrv.frpjump.ui.profiles.ForgetState
import ru.adsrv.frpjump.ui.profiles.ProfileItem
import ru.adsrv.frpjump.ui.profiles.ProfileTile
import ru.adsrv.frpjump.ui.shortTime
import ru.adsrv.frpjump.update.UpdateState
import java.time.Instant

/** Which sheet or dialog of the settings is open. */
private enum class Open { PORTS, POLL, LOCK_AFTER, SSH_KEY, LANGUAGE, THEME, HISTORY, FORGET, NEEDS_SCREEN_LOCK, UPDATE }

/**
 * Settings as the mockup groups them: profile, tunnels, background,
 * security, app. Rows show their value; rarely changed ones open a sheet.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    /** The current profile, for the profile group. */
    profile: ProfileItem?,
    forget: ForgetState?,
    onEditProfile: (id: String) -> Unit,
    onForget: (id: String) -> Unit,
    /** The back arrow of the stack screen; null shows none. */
    onBack: (() -> Unit)?,
    onChat: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val assistantProvider by vm.assistantProvider.collectAsStateWithLifecycle()
    var toolsSetup by rememberSaveable { mutableStateOf(false) }
    var providerSetup by rememberSaveable { mutableStateOf(false) }
    var ownerSetup by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<Open?>(null) }
    // Above the licenses' early return, so the settings come back scrolled where they were.
    val scroll = rememberScrollState()
    if (toolsSetup) {
        AssistantToolsRoute(vm.tools, onBack = { toolsSetup = false })
        return
    }
    if (ownerSetup) {
        AssistantOwnerInstructionsScreen(settings.assistantOwnerInstructions, onSave = vm::setAssistantOwnerInstructions, onBack = { ownerSetup = false })
        return
    }
    if (providerSetup) {
        AssistantProviderScreen(assistantProvider, onSave = { draft, done -> vm.saveAssistantProvider(draft, done); Unit },
            onModels = vm::assistantModels, onReload = { vm.reloadAssistantProvider() }, onBack = { providerSetup = false })
        return
    }
    // Components and licenses: a screen of its own over the settings.
    var licenses by rememberSaveable { mutableStateOf(false) }
    if (licenses) {
        LicensesScreen(onBack = { licenses = false })
        return
    }
    // MQTT plugins: a screen of its own over the settings, as the licenses.
    var plugins by rememberSaveable { mutableStateOf(false) }
    if (plugins) {
        PluginsScreen(onBack = { plugins = false })
        return
    }
    var mqttLimits by rememberSaveable { mutableStateOf(false) }
    if (mqttLimits) {
        MqttIngressScreen(settings.mqttIngress, onSave = { limits, complete -> vm.setMqttIngress(limits, complete) },
            onBack = { mqttLimits = false })
        return
    }
    var mqttCache by rememberSaveable { mutableStateOf(false) }
    if (mqttCache) {
        MqttCacheScreen(settings.mqttCache, onSave = { limits, complete -> vm.setMqttCache(limits, complete) },
            onBack = { mqttCache = false })
        return
    }
    val pluginsSummary by vm.plugins.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = AppTheme.colors
    // Read again on every return: both may change in the system settings meanwhile.
    var batteryExempt by remember { mutableStateOf(true) }
    var screenLock by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        screenLock = AppLock.hasScreenLock(context)
        onPauseOrDispose { }
    }
    var lockBusy by remember { mutableStateOf(false) }
    val lockTitle = stringResource(R.string.lock_turn_on_title)
    val lockSubtitle = stringResource(R.string.lock_prompt_subtitle)
    val onAppLock = { on: Boolean ->
        when {
            !on -> vm.turnOffAppLock()
            !AppLock.hasScreenLock(context) -> {
                screenLock = false
                open = Open.NEEDS_SCREEN_LOCK
            }
            else -> context.findActivity()?.let { activity ->
                lockBusy = true
                vm.turnOnAppLock(activity, lockTitle, lockSubtitle) { result ->
                    lockBusy = false
                    val text = when (result) {
                        AuthResult.Success -> null
                        AuthResult.Cancelled -> context.getString(R.string.lock_not_turned_on)
                        is AuthResult.Failed -> context.getString(R.string.lock_failed, result.message)
                    }
                    text?.let { context.notice(it, NoticeKind.WARN, long = true) }
                }
            }
        }
        Unit
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            }
            Text(stringResource(R.string.settings), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        profile?.let {
            ProfileGroup(
                profile = it,
                forget = forget?.takeIf { f -> f.id == it.id },
                onEdit = { onEditProfile(it.id) },
                onForget = { open = Open.FORGET },
            )
        }
        Group(stringResource(R.string.settings_tunnels)) {
            SwitchRow(
                tile = { IconTile(SwapGlyph) },
                label = stringResource(R.string.setting_direct),
                hint = stringResource(R.string.setting_direct_hint),
                checked = settings.direct,
                onChange = vm::setDirect,
            )
            RowDivider()
            SettingRow(
                tile = { GlyphTile("#") },
                label = stringResource(R.string.local_ports),
                onClick = { open = Open.PORTS },
            ) { ValueChevron("${settings.localPortFirst}–${settings.localPortLast}") }
        }
        Group(stringResource(R.string.settings_terminal)) {
            SettingRow(
                tile = { GlyphTile("≡") },
                label = stringResource(R.string.terminal_history_rows),
                hint = stringResource(R.string.terminal_history_hint),
                onClick = { open = Open.HISTORY },
            ) { ValueChevron(settings.terminalHistoryRows.toString()) }
            RowDivider()
            SwitchRow(
                tile = { GlyphTile(">_") },
                label = stringResource(R.string.setting_terminal_direct_input),
                hint = stringResource(R.string.setting_terminal_direct_input_hint),
                checked = settings.terminalDirectInput,
                onChange = vm::setTerminalDirectInput,
            )
            RowDivider()
            SettingRow(
                tile = { GlyphTile("⌨") },
                label = stringResource(R.string.terminal_text_input),
                hint = stringResource(R.string.terminal_text_input_hint),
                onClick = null,
            ) {}
        }
        Group(stringResource(R.string.assistant_title)) {
            AssistantChatEntry(onChat)
            RowDivider()
            AssistantToolsEntry { toolsSetup = true }
            RowDivider()
            AssistantOwnerInstructionsEntry(settings.assistantOwnerInstructions) { ownerSetup = true }
            RowDivider()
            AssistantModuleSetting(settings.assistantEnabled, vm::setAssistantEnabled)
            RowDivider()
            SettingRow(tile = { GlyphTile("API") }, label = stringResource(R.string.assistant_provider_title),
                hint = if (assistantProvider.problem != null) stringResource(R.string.assistant_provider_read_failed)
                    else assistantProvider.settings?.model?.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_provider_not_selected),
                onClick = { providerSetup = true }) { Chevron() }
            RowDivider()
            AssistantChatTextSizeSetting(settings.assistantChatTextSize, vm::setAssistantChatTextSize)
        }
        Group(stringResource(R.string.settings_background)) {
            SettingRow(
                tile = { IconTile(RepeatGlyph) },
                label = stringResource(R.string.background_poll),
                hint = stringResource(R.string.background_poll_row_hint),
                onClick = { open = Open.POLL },
            ) { ValueChevron(pollLabel(settings.backgroundPoll)) }
            if (!batteryExempt) {
                RowDivider()
                BatteryRow()
            }
        }
        Group(stringResource(R.string.settings_security)) {
            SettingRow(
                tile = { IconTile(KeyGlyph) },
                label = stringResource(R.string.ssh_keys),
                hint = sshKeysSummary(state) ?: "…",
                hintColor = if (state.sshPublicKeyError != null) colors.error else colors.muted,
                onClick = { open = Open.SSH_KEY },
            ) { Chevron() }
            RowDivider()
            SwitchRow(
                tile = { IconTile(FingerprintGlyph) },
                label = stringResource(R.string.setting_app_lock),
                hint = when {
                    settings.appLock -> stringResource(R.string.setting_app_lock_hint_after, lockAfterLabel(settings.appLockAfter))
                    screenLock -> stringResource(R.string.setting_app_lock_hint)
                    else -> stringResource(R.string.setting_app_lock_needs_screen_lock)
                },
                hintColor = if (screenLock || settings.appLock) colors.muted else colors.warning,
                checked = settings.appLock,
                busy = lockBusy,
                onChange = onAppLock,
            )
            if (settings.appLock) {
                RowDivider()
                SettingRow(
                    tile = { IconTile(FingerprintGlyph) },
                    label = stringResource(R.string.setting_lock_after),
                    onClick = { open = Open.LOCK_AFTER },
                ) { ValueChevron(lockAfterLabel(settings.appLockAfter)) }
            }
            RowDivider()
            SwitchRow(
                tile = { IconTile(EyeOffGlyph) },
                label = stringResource(R.string.setting_hide_in_recents),
                hint = stringResource(R.string.setting_hide_in_recents_hint),
                checked = settings.hideInRecents,
                onChange = vm::setHideInRecents,
            )
        }
        Group(stringResource(R.string.settings_app)) {
            // Read again with every new language: it may also be set in the system's app settings.
            val locales = LocalConfiguration.current.locales
            val language = remember(locales) { currentAppLanguage() }
            SettingRow(
                tile = { GlyphTile(stringResource(R.string.language_glyph)) },
                label = stringResource(R.string.language),
                onClick = { open = Open.LANGUAGE },
            ) { ValueChevron(languageLabel(language)) }
            RowDivider()
            SettingRow(
                tile = { IconTile(ThemeGlyph) },
                label = stringResource(R.string.theme),
                onClick = { open = Open.THEME },
            ) { ValueChevron(themeLabel(settings.theme)) }
            RowDivider()
            VersionRow(update, onCheck = vm::checkForUpdates, onUpdate = { open = Open.UPDATE })
            RowDivider()
            SettingRow(
                tile = { IconTile(PluginGlyph) },
                label = stringResource(R.string.mqtt_ingress_title),
                hint = stringResource(R.string.mqtt_ingress_row_hint),
                onClick = { mqttLimits = true },
            ) { Chevron() }
            RowDivider()
            SettingRow(
                tile = { IconTile(PluginGlyph) },
                label = stringResource(R.string.mqtt_cache_title),
                hint = stringResource(R.string.mqtt_cache_row_hint),
                onClick = { mqttCache = true },
            ) { Chevron() }
            RowDivider()
            SettingRow(
                tile = { IconTile(PluginGlyph) },
                label = stringResource(R.string.plugins_title),
                hint = pluginsSummary?.let { p ->
                    stringResource(R.string.plugins_row_hint, p.on, p.total) +
                        if (p.anyExceptions) " " + stringResource(R.string.plugins_row_hint_exceptions) else ""
                } ?: "…",
                onClick = { plugins = true },
            ) { Chevron() }
            RowDivider()
            SettingRow(
                tile = { IconTile(FileGlyph) },
                label = stringResource(R.string.licenses),
                hint = stringResource(R.string.licenses_hint),
                onClick = { licenses = true },
            ) { Chevron() }
        }
    }

    when (open) {
        Open.PORTS -> PortsSheet(settings.localPortFirst, settings.localPortLast, vm::setLocalPorts, onDismiss = { open = null })
        Open.POLL -> ChoiceSheet(
            title = stringResource(R.string.background_poll),
            options = BackgroundPoll.entries,
            selected = settings.backgroundPoll,
            label = { pollLabel(it) },
            onPick = vm::setBackgroundPoll,
            onDismiss = { open = null },
            footer = {
                Text(
                    if (settings.backgroundPoll == BackgroundPoll.OFF) {
                        stringResource(R.string.background_poll_off_hint)
                    } else {
                        stringResource(R.string.background_poll_on_hint, pollLabel(settings.backgroundPoll))
                    } + " " + stringResource(R.string.background_poll_open_hint),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                )
            },
        )
        Open.LOCK_AFTER -> ChoiceSheet(
            title = stringResource(R.string.setting_lock_after),
            options = AppLockAfter.entries,
            selected = settings.appLockAfter,
            label = { lockAfterLabel(it) },
            onPick = vm::setAppLockAfter,
            onDismiss = { open = null },
            footer = { Text(stringResource(R.string.setting_lock_after_hint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted) },
        )
        Open.HISTORY -> ChoiceSheet(
            title = stringResource(R.string.terminal_history_rows),
            options = TERMINAL_HISTORY_CHOICES,
            selected = settings.terminalHistoryRows,
            label = { it.toString() },
            onPick = vm::setTerminalHistoryRows,
            onDismiss = { open = null },
            footer = { Text(stringResource(R.string.terminal_history_hint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted) },
        )
        Open.SSH_KEY -> SshKeySheet(state, vm, onDismiss = { open = null })
        Open.LANGUAGE -> ChoiceSheet(
            title = stringResource(R.string.language),
            options = AppLanguage.entries,
            selected = currentAppLanguage(),
            label = { languageLabel(it) },
            onPick = vm::setLanguage,
            onDismiss = { open = null },
        )
        Open.THEME -> ChoiceSheet(
            title = stringResource(R.string.theme),
            options = ThemeMode.entries,
            selected = settings.theme,
            label = { themeLabel(it) },
            onPick = vm::setTheme,
            onDismiss = { open = null },
        )
        Open.FORGET -> if (profile != null) {
            ForgetProfileDialog(
                item = profile,
                busyElsewhere = forget?.busy == true,
                onConfirm = {
                    open = null
                    onForget(profile.id)
                },
                onDismiss = { open = null },
            )
        }
        Open.NEEDS_SCREEN_LOCK -> AppAlertDialog(
            onDismissRequest = { open = null },
            title = { Text(stringResource(R.string.setting_app_lock)) },
            text = { Text(stringResource(R.string.lock_needs_screen_lock_text)) },
            confirmButton = {
                TextButton(onClick = {
                    open = null
                    openSecuritySettings(context)
                }) { Text(stringResource(R.string.lock_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.cancel)) } },
        )
        Open.UPDATE -> update.available?.let {
            UpdateSheet(
                release = it,
                download = update.download,
                onDownload = vm::downloadUpdate,
                onClearError = vm::clearUpdateError,
                onDismiss = { open = null },
            )
        }
        null -> Unit
    }
}

/** Shared entry used by Settings and the isolated navigation component check. */
@Composable
internal fun AssistantChatEntry(onChat: () -> Unit) {
    SettingRow(tile = { GlyphTile("LLM") }, label = stringResource(R.string.chat_title),
        modifier = Modifier.testTag("assistant_chat_entry"), onClick = onChat) { Chevron() }
}

/**
 * "Version 0.2.0 · frpc 0.70.0", as the mockup: with a newer release, its
 * version and an "Update" pill that opens [UpdateSheet]; otherwise when it
 * was last checked, a tap checking again with the progress in place.
 */
@Composable
private fun VersionRow(update: UpdateState, onCheck: () -> Unit, onUpdate: () -> Unit) {
    val colors = AppTheme.colors
    val available = update.available
    val hint = when {
        available != null -> stringResource(R.string.update_available, available.version)
        update.checking -> stringResource(R.string.update_checking)
        update.checkError != null -> update.checkError
        update.checkedAt != null -> stringResource(R.string.update_up_to_date, shortTime(Instant.ofEpochMilli(update.checkedAt)))
        else -> stringResource(R.string.update_check_hint)
    }
    SettingRow(
        tile = { GlyphTile("i") },
        label = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.FRPC_VERSION),
        hint = hint,
        hintColor = if (available == null && update.checkError != null) colors.error else colors.muted,
        enabled = available != null || !update.checking,
        onClick = if (available != null) onUpdate else onCheck,
    ) {
        when {
            available != null -> Text(
                stringResource(R.string.update_action),
                fontSize = 14.sp,
                color = colors.onAccentContainer,
                modifier = Modifier
                    .background(colors.accentContainer, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            update.checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** A group's title, as the mockup sets it (small capitals, muted), over a card of rows; no title: the card alone. */
@Composable
internal fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(
            title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.72.sp,
            color = AppTheme.colors.muted,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Surface(color = AppTheme.colors.card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = AppTheme.colors.divider)

/** A 34 dp tile with an icon. */
@Composable
internal fun IconTile(icon: ImageVector) {
    Box(
        Modifier
            .size(34.dp)
            .background(AppTheme.colors.tile, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.colors.ink, modifier = Modifier.size(18.dp))
    }
}

/** A 34 dp tile with a glyph, as the mockup's rows start. */
@Composable
internal fun GlyphTile(glyph: String, background: Color = AppTheme.colors.tile, ink: Color = AppTheme.colors.ink) {
    Box(
        Modifier
            .size(34.dp)
            .background(background, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink)
    }
}

/** A row: tile, label with an optional hint, and what is on the right (value, chevron, switch). */
@Composable
internal fun SettingRow(
    tile: @Composable () -> Unit,
    label: String,
    onClick: (() -> Unit)?,
    hint: String? = null,
    labelColor: Color = AppTheme.colors.ink,
    hintColor: Color = AppTheme.colors.muted,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tile()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = labelColor)
            hint?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = hintColor) }
        }
        trailing()
    }
}

/** A row the whole of which toggles its switch; [busy] shows progress in place of the switch. */
@Composable
internal fun SwitchRow(
    tile: @Composable () -> Unit,
    label: String,
    hint: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hintColor: Color = AppTheme.colors.muted,
    busy: Boolean = false,
) {
    SettingRow(
        tile = tile,
        label = label,
        hint = hint,
        hintColor = hintColor,
        onClick = null,
        modifier = Modifier.toggleable(value = checked, enabled = !busy, role = Role.Switch, onValueChange = onChange),
    ) {
        if (busy) {
            Box(Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
internal fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.faint)

@Composable
private fun ValueChevron(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = 14.sp, color = AppTheme.colors.muted, maxLines = 1)
        Chevron()
    }
}

/** The current profile: its name and colour, and "Forget profile" with its progress or failure. */
@Composable
private fun ProfileGroup(profile: ProfileItem, forget: ForgetState?, onEdit: () -> Unit, onForget: () -> Unit) {
    val colors = AppTheme.colors
    Group(stringResource(R.string.settings_profile_group, profile.name)) {
        SettingRow(
            tile = { ProfileTile(profile.letter, profile.color, size = 34.dp) },
            label = stringResource(R.string.name_and_color),
            hint = profile.server?.let { stringResource(R.string.profile_server, it.host, it.deviceName) },
            onClick = onEdit,
        ) { Chevron() }
        RowDivider()
        SettingRow(
            tile = {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(colors.errorContainer, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(18.dp))
                }
            },
            label = stringResource(R.string.forget_profile),
            labelColor = colors.error,
            hint = forget?.error ?: stringResource(R.string.forget_profile_hint),
            hintColor = if (forget?.error != null) colors.error else colors.muted,
            // Only once it is known what it would remove.
            enabled = forget?.busy != true && profile.serverLoaded,
            onClick = onForget,
        ) {
            if (forget?.busy == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * "Battery · Android may stop tunnels · Fix", only while the app is not
 * exempt from battery optimisation: then Android may stop the tunnels
 * service, and may not let it start again by itself after killing the
 * process. "Fix" asks for the exemption; back from the system dialog the
 * row goes once it is granted.
 */
@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val colors = AppTheme.colors
    SettingRow(
        tile = { GlyphTile("!", colors.warningContainer, colors.onWarningContainer) },
        label = stringResource(R.string.battery),
        hint = stringResource(R.string.battery_hint),
        onClick = { askBatteryExemption(context) },
    ) {
        Text(
            stringResource(R.string.battery_fix),
            fontSize = 14.sp,
            color = colors.onWarningContainer,
            modifier = Modifier
                .background(colors.warningContainer, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Local ports: the range, checked before it is kept. */
@Composable
private fun PortsSheet(first: Int, last: Int, onSave: (Int?, Int?) -> PortRangeProblem?, onDismiss: () -> Unit) {
    var firstText by rememberSaveable { mutableStateOf(first.toString()) }
    var lastText by rememberSaveable { mutableStateOf(last.toString()) }
    var problem by remember { mutableStateOf<PortRangeProblem?>(null) }
    AppSheet(title = stringResource(R.string.local_ports), onDismiss = onDismiss) {
        Text(stringResource(R.string.local_ports_hint), fontSize = 13.sp, lineHeight = 18.sp, color = AppTheme.colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val digits = { text: String -> text.filter { it.isDigit() }.take(5) }
            Field(stringResource(R.string.local_ports_from), firstText, { firstText = digits(it); problem = null }, Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number)
            Field(stringResource(R.string.local_ports_to), lastText, { lastText = digits(it); problem = null }, Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number)
        }
        problem?.let { Text(portRangeText(it), color = AppTheme.colors.error, fontSize = 14.sp) }
        MainButton(
            text = stringResource(R.string.save),
            busy = false,
            enabled = problem == null,
            onClick = {
                problem = onSave(firstText.toIntOrNull(), lastText.toIntOrNull())
                if (problem == null) onDismiss()
            },
        )
    }
}

/** The app's SSH keys, the phone's and an imported one, each with its line to add on devices: copy it, share it. */
@Composable
private fun SshKeySheet(state: SettingsUiState, vm: SettingsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    // A picked key file waits here for its passphrase; never saved across recreation.
    var pickedKey by remember { mutableStateOf<String?>(null) }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedKey = readSmallText(context, uri)
        if (uri != null && pickedKey == null) context.notice(context.getString(R.string.key_file_unreadable), NoticeKind.WARN)
    }
    AppSheet(title = stringResource(R.string.ssh_keys), subtitle = sshKeysSummary(state), onDismiss = onDismiss) {
        Text(stringResource(R.string.ssh_keys_hint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
        if (state.sshPublicKey != null) {
            KeyBlock(SshKeySlot.PHONE, state.sshPublicKey)
        } else {
            Text(state.sshPublicKeyError ?: "…", fontSize = 13.sp, color = if (state.sshPublicKeyError != null) colors.error else colors.muted)
        }
        HorizontalDivider(color = colors.divider)
        val imported = state.importedKey
        if (imported != null) {
            KeyBlock(SshKeySlot.IMPORTED, imported)
            Text(stringResource(R.string.imported_key_tried_first), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
        } else {
            Text(stringResource(R.string.imported_key), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.imported_key_hint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
        }
        state.importError?.let { Text(it, color = colors.error, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                vm.clearImportError()
                pickKey.launch(arrayOf("*/*"))
            }, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (imported == null) R.string.import_key else R.string.replace_key))
            }
            if (imported != null) {
                OutlinedButton(onClick = vm::removeImportedKey, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.remove_key)) }
            }
        }
    }
    pickedKey?.let { key ->
        PassphraseDialog(
            onImport = { passphrase ->
                pickedKey = null
                vm.importKey(key, passphrase)
            },
            onDismiss = { pickedKey = null },
        )
    }
}

/** The row's and the sheet's summary: why the phone's key is missing, else the one key's fingerprint, or which two keys there are. */
@Composable
private fun sshKeysSummary(state: SettingsUiState): String? =
    state.sshPublicKeyError ?: if (state.importedKey != null) stringResource(R.string.ssh_keys_both) else state.sshKeySummary

/**
 * One key: its name, type and fingerprint with Copy and Share in the title
 * row, then its line, folded to 3 lines when longer; a tap unfolds it.
 */
@Composable
private fun KeyBlock(slot: SshKeySlot, line: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colors = AppTheme.colors
    val imported = slot == SshKeySlot.IMPORTED
    val title = keyTitle(slot)
    var expanded by rememberSaveable(slot.name) { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                keySummary(line)?.let { Text(it, fontSize = 13.sp, color = colors.muted) }
            }
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(line))
                // Android 13+ shows its own confirmation of a copy.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    context.notice(context.getString(R.string.copied), NoticeKind.DONE, key = "copied")
                }
            }) {
                Icon(CopyGlyph, contentDescription = stringResource(if (imported) R.string.copy_imported_key else R.string.copy_phone_key))
            }
            val share = LocalShareText.current
            IconButton(onClick = { share(context, line, title) }) {
                Icon(ShareGlyph, contentDescription = stringResource(if (imported) R.string.share_imported_key else R.string.share_phone_key))
            }
        }
        // Clickable only when there is something to fold or unfold; selectable only when the whole line shows, so Select all never copies a cut key.
        val foldable = overflows || expanded
        val text: @Composable () -> Unit = {
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onCode,
                maxLines = if (expanded) Int.MAX_VALUE else KEY_LINE_FOLDED,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.padding(12.dp),
            )
        }
        Surface(
            color = colors.code,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ssh-key-line-${slot.name}")
                .then(
                    if (foldable) {
                        Modifier.clickable(onClickLabel = stringResource(if (expanded) R.string.fold_key else R.string.show_whole_key)) { expanded = !expanded }
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (expanded || !overflows) SelectionContainer { text() } else text()
        }
    }
}

private const val KEY_LINE_FOLDED = 3

/** How a key line is shared (context, line, the key's name); a test swaps it to see what each key's icon hands over. */
internal val LocalShareText = staticCompositionLocalOf<(Context, String, String) -> Unit> { { context, text, title -> shareText(context, text, title) } }

/** The span of a poll interval, "30 s", "5 min", or "Off". */
@Composable
private fun pollLabel(poll: BackgroundPoll): String {
    val seconds = ((poll.ms ?: return stringResource(R.string.poll_off)) / 1000).toInt()
    return if (seconds < 60) {
        pluralStringResource(R.plurals.poll_seconds, seconds, seconds)
    } else {
        pluralStringResource(R.plurals.poll_minutes, seconds / 60, seconds / 60)
    }
}

/** "Immediately", "30 s", "5 min". */
@Composable
private fun lockAfterLabel(after: AppLockAfter): String {
    val seconds = (after.ms / 1000).toInt()
    return when {
        seconds == 0 -> stringResource(R.string.lock_after_immediately)
        seconds < 60 -> pluralStringResource(R.plurals.poll_seconds, seconds, seconds)
        else -> pluralStringResource(R.plurals.poll_minutes, seconds / 60, seconds / 60)
    }
}

/** The languages are named in their own words, so either can be found. */
@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.RUSSIAN -> stringResource(R.string.language_russian)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
}

@Composable
private fun themeLabel(theme: ThemeMode): String = when (theme) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
}

@Composable
private fun portRangeText(problem: PortRangeProblem): String = when (problem) {
    PortRangeProblem.MISSING -> stringResource(R.string.ports_missing)
    PortRangeProblem.OUTSIDE -> stringResource(R.string.ports_outside, MIN_LOCAL_PORT, MAX_LOCAL_PORT)
    PortRangeProblem.REVERSED -> stringResource(R.string.ports_reversed)
    PortRangeProblem.TOO_SMALL -> pluralStringResource(R.plurals.ports_too_small, MIN_LOCAL_PORTS, MIN_LOCAL_PORTS)
}

/** Asks to exempt the app (a sideloaded app may); the list of all apps as a fallback. */
@SuppressLint("BatteryLife")
private fun askBatteryExemption(context: Context) {
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    if (!startFirst(context, ask, list)) {
        context.notice(context.getString(R.string.battery_no_settings), NoticeKind.WARN)
    }
}

/** Where a screen lock is set; the system settings as a fallback. */
private fun openSecuritySettings(context: Context) {
    if (!startFirst(context, Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
        context.notice(context.getString(R.string.lock_no_settings), NoticeKind.WARN)
    }
}

/** Starts the first of [intents] some app takes; false when none does. */
private fun startFirst(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
    return false
}

/** The activity under a composable's context, for the fingerprint prompt. */
private fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PassphraseDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_passphrase)) },
        text = {
            SecretField(passphrase, { passphrase = it }, stringResource(R.string.key_passphrase_label))
        },
        confirmButton = { TextButton(onClick = { onImport(passphrase) }) { Text(stringResource(R.string.import_key_short)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A key file is a few KiB; anything larger is not one. */
private fun readSmallText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(MAX_KEY_FILE + 1)
        if (bytes.size > MAX_KEY_FILE) null else String(bytes, Charsets.UTF_8)
    }
} catch (e: Exception) {
    null
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** The chooser that shares [text]; its title is [title], the key's name. */
internal fun shareTextIntent(text: String, title: String): Intent =
    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)

private fun shareText(context: Context, text: String, title: String) {
    context.startActivity(shareTextIntent(text, title))
}

private const val MAX_KEY_FILE = 64 * 1024
