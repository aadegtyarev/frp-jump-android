package ru.adsrv.frpjump.ui.settings

import android.content.Context
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.adsrv.frpjump.settings.BackgroundPoll
import ru.adsrv.frpjump.settings.MAX_LOCAL_PORT
import ru.adsrv.frpjump.settings.MIN_LOCAL_PORT
import ru.adsrv.frpjump.settings.MIN_LOCAL_PORTS
import ru.adsrv.frpjump.settings.PortRangeProblem
import ru.adsrv.frpjump.locale.AppLanguage
import ru.adsrv.frpjump.locale.currentAppLanguage
import androidx.compose.ui.res.pluralStringResource
import ru.adsrv.frpjump.ui.SecretField
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.profiles.ForgetProfileDialog
import ru.adsrv.frpjump.ui.profiles.ForgetState
import ru.adsrv.frpjump.ui.profiles.ProfileItem
import ru.adsrv.frpjump.ui.profiles.ProfileTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    /** The current profile, for the profile group. */
    profile: ProfileItem?,
    forget: ForgetState?,
    onEditProfile: (id: String) -> Unit,
    onForget: (id: String) -> Unit,
    /** Null when Settings is a tab: no back arrow. */
    onBack: (() -> Unit)?,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // A picked key file waits here for its passphrase; never saved across recreation.
    var pickedKey by remember { mutableStateOf<String?>(null) }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedKey = readSmallText(context, uri)
        if (uri != null && pickedKey == null) Toast.makeText(context, context.getString(R.string.key_file_unreadable), Toast.LENGTH_SHORT).show()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            profile?.let {
                ProfileGroup(
                    profile = it,
                    forget = forget?.takeIf { f -> f.id == it.id },
                    onEdit = { onEditProfile(it.id) },
                    onForget = { confirm = true },
                )
            }
            SectionTitle(stringResource(R.string.settings_tunnels))
            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_direct)) },
                supportingContent = { Text(stringResource(R.string.setting_direct_hint), style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Switch(checked = settings.direct, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = settings.direct, role = Role.Switch, onValueChange = vm::setDirect),
            )
            LocalPortsItem(settings.localPortFirst, settings.localPortLast, vm::setLocalPorts)
            SectionTitle(stringResource(R.string.settings_background))
            BackgroundPollItem(settings.backgroundPoll, vm::setBackgroundPoll)
            BatteryItem()
            SectionTitle(stringResource(R.string.kind_ssh))
            ListItem(
                headlineContent = { Text(stringResource(R.string.ssh_public_key)) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(R.string.ssh_public_key_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SelectionContainer {
                            Text(
                                state.sshPublicKey ?: state.sshPublicKeyError ?: "…",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                },
            )
            state.sshPublicKey?.let { key ->
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = { shareText(context, key) }) { Text(stringResource(R.string.share)) }
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.imported_key)) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(if (state.importedKey != null) R.string.imported_key_tried_first else R.string.imported_key_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.importedKey?.let {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        state.importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                },
            )
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = {
                    vm.clearImportError()
                    pickKey.launch(arrayOf("*/*"))
                }) { Text(stringResource(if (state.importedKey == null) R.string.import_key else R.string.replace_key)) }
                if (state.importedKey != null) TextButton(onClick = vm::removeImportedKey) { Text(stringResource(R.string.remove_key)) }
            }
            SectionTitle(stringResource(R.string.settings_app))
            LanguageItem(vm::setLanguage)
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
    if (confirm && profile != null) {
        ForgetProfileDialog(
            item = profile,
            busyElsewhere = forget?.busy == true,
            onConfirm = {
                confirm = false
                onForget(profile.id)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** A group's title, as the mockup sets it: small capitals, muted. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.72.sp,
        color = AppTheme.colors.muted,
        modifier = Modifier.padding(start = 22.dp, top = 20.dp, end = 22.dp, bottom = 6.dp),
    )
}

/** The current profile: its name and colour, and "Forget profile" with its progress or failure. */
@Composable
private fun ProfileGroup(profile: ProfileItem, forget: ForgetState?, onEdit: () -> Unit, onForget: () -> Unit) {
    val colors = AppTheme.colors
    SectionTitle(stringResource(R.string.settings_profile_group, profile.name))
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column {
            GroupRow(
                tile = { ProfileTile(profile.letter, profile.color, size = 34.dp) },
                label = stringResource(R.string.name_and_color),
                hint = profile.server?.let { stringResource(R.string.profile_server, it.host, it.deviceName) },
                onClick = onEdit,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.faint)
            }
            HorizontalDivider(color = colors.divider)
            GroupRow(
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
}

@Composable
private fun GroupRow(
    tile: @Composable () -> Unit,
    label: String,
    hint: String?,
    onClick: () -> Unit,
    labelColor: Color = AppTheme.colors.ink,
    hintColor: Color = AppTheme.colors.muted,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tile()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = labelColor)
            hint?.let { Text(it, fontSize = 13.sp, color = hintColor) }
        }
        trailing()
    }
}

@Composable
private fun LocalPortsItem(first: Int, last: Int, onSave: (Int?, Int?) -> PortRangeProblem?) {
    var firstText by rememberSaveable(first) { mutableStateOf(first.toString()) }
    var lastText by rememberSaveable(last) { mutableStateOf(last.toString()) }
    var problem by remember { mutableStateOf<PortRangeProblem?>(null) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.local_ports)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.local_ports_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PortField(stringResource(R.string.local_ports_from), firstText, Modifier.weight(1f)) { firstText = it; problem = null }
                    PortField(stringResource(R.string.local_ports_to), lastText, Modifier.weight(1f)) { lastText = it; problem = null }
                    TextButton(
                        enabled = firstText != first.toString() || lastText != last.toString(),
                        onClick = { problem = onSave(firstText.toIntOrNull(), lastText.toIntOrNull()) },
                    ) { Text(stringResource(R.string.save)) }
                }
                problem?.let { Text(portRangeText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

@Composable
private fun PortField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundPollItem(selected: BackgroundPoll, onSelect: (BackgroundPoll) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.background_poll)) },
        supportingContent = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackgroundPoll.entries.forEach { poll ->
                        FilterChip(selected = poll == selected, onClick = { onSelect(poll) }, label = { Text(pollLabel(poll)) })
                    }
                }
                Text(
                    if (selected == BackgroundPoll.OFF) {
                        stringResource(R.string.background_poll_off_hint)
                    } else {
                        stringResource(R.string.background_poll_on_hint, pollEvery(selected))
                    } + " " + stringResource(R.string.background_poll_open_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/** The chip of a poll interval: "Off", "30 s", "1 min". */
@Composable
private fun pollLabel(poll: BackgroundPoll): String = when (poll) {
    BackgroundPoll.OFF -> stringResource(R.string.poll_off)
    else -> pollEvery(poll)
}

/** A poll interval as a span of time: "30 s", "5 min". */
@Composable
private fun pollEvery(poll: BackgroundPoll): String {
    val seconds = ((poll.ms ?: 0L) / 1000).toInt()
    return if (seconds < 60) {
        pluralStringResource(R.plurals.poll_seconds, seconds, seconds)
    } else {
        pluralStringResource(R.plurals.poll_minutes, seconds / 60, seconds / 60)
    }
}

@Composable
private fun portRangeText(problem: PortRangeProblem): String = when (problem) {
    PortRangeProblem.MISSING -> stringResource(R.string.ports_missing)
    PortRangeProblem.OUTSIDE -> stringResource(R.string.ports_outside, MIN_LOCAL_PORT, MAX_LOCAL_PORT)
    PortRangeProblem.REVERSED -> stringResource(R.string.ports_reversed)
    PortRangeProblem.TOO_SMALL -> stringResource(R.string.ports_too_small, MIN_LOCAL_PORTS)
}

/**
 * Settings › Language: as the system, Russian or English, applied at once.
 * The languages are named in their own words, so either can be found.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageItem(onSelect: (AppLanguage) -> Unit) {
    // Read again whenever the activity comes back with a new language.
    var selected by remember { mutableStateOf(currentAppLanguage()) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.language)) },
        supportingContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = language == selected,
                        onClick = {
                            selected = language
                            onSelect(language)
                        },
                        label = {
                            Text(
                                when (language) {
                                    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                                    AppLanguage.RUSSIAN -> stringResource(R.string.language_russian)
                                    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
                                },
                            )
                        },
                    )
                }
            }
        },
    )
}

/**
 * "Battery · Android may stop tunnels · Fix", only while the app is not
 * exempt from battery optimisation: then Android may stop the tunnels
 * service, and may not let it start again by itself after killing the
 * process. "Fix" asks for the exemption; back from the system dialog the
 * row goes once it is granted.
 */
@Composable
private fun BatteryItem() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    // Back from the system screen: read it again.
    LifecycleResumeEffect(Unit) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (exempt) return
    val colors = AppTheme.colors
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp),
    ) {
        GroupRow(
            tile = {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(colors.warningContainer, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("!", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onWarningContainer)
                }
            },
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
}

/** Asks to exempt the app (a sideloaded app may); the list of all apps as a fallback. */
@SuppressLint("BatteryLife")
private fun askBatteryExemption(context: Context) {
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    for (intent in listOf(ask, list)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
    Toast.makeText(context, context.getString(R.string.battery_no_settings), Toast.LENGTH_SHORT).show()
}

@Composable
private fun PassphraseDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
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

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.ssh_public_key)))
}

private const val MAX_KEY_FILE = 64 * 1024
