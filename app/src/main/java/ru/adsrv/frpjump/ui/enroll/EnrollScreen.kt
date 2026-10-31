package ru.adsrv.frpjump.ui.enroll

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FormCard
import ru.adsrv.frpjump.ui.FormScreen
import ru.adsrv.frpjump.ui.ScanGlyph
import ru.adsrv.frpjump.ui.findActivity
import ru.adsrv.frpjump.ui.profiles.ProfileColorPicker
import ru.adsrv.frpjump.ui.profiles.ProfileNameField

/**
 * "New profile", as the first screen or from the profile list ([onBack]).
 * [onOtherProfiles] offers the profile list when other profiles exist
 * and there is nothing to go back to.
 */
@Composable
fun EnrollScreen(vm: EnrollViewModel, onEnrolled: () -> Unit, onBack: (() -> Unit)?, onOtherProfiles: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    var scanning by rememberSaveable { mutableStateOf(false) }
    // Restored after the camera was taken away in the settings: back to the form.
    val context = LocalContext.current
    if (scanning && !cameraAllowed(context)) SideEffect { scanning = false }
    if (scanning && cameraAllowed(context)) {
        QrScannerScreen(
            onScanned = { text -> vm.onScanned(text).also { if (it) scanning = false } },
            onClose = { scanning = false },
        )
        return
    }
    FormScreen(
        title = stringResource(R.string.new_profile),
        action = stringResource(R.string.enroll_submit),
        busy = state.busy,
        onAction = { vm.submit(onEnrolled) },
        onBack = onBack,
        actionEnabled = state.text.isNotBlank(),
    ) {
        state.notice?.let { Text(it, fontSize = 14.sp, color = AppTheme.colors.ink) }
        InviteCard()
        LineCard(state, vm, onScan = { scanning = true })
        FormCard(title = stringResource(R.string.enroll_step_name)) {
            ProfileNameField(state.profileName, vm::onProfileName, state.profileNameError, enabled = !state.busy)
            ProfileColorPicker(state.color, vm::onColor, enabled = !state.busy)
        }
        onOtherProfiles?.let {
            TextButton(onClick = it) { Text(stringResource(R.string.other_profiles)) }
        }
    }
}

@Composable
private fun InviteCard() {
    val colors = AppTheme.colors
    FormCard(title = stringResource(R.string.enroll_step_invite)) {
        Text(stringResource(R.string.enroll_step_invite_text), fontSize = 14.sp, color = colors.muted)
        Text(
            stringResource(R.string.enroll_command),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = colors.onCode,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.code, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** "Scan QR code", or the pasted line, with what was understood of it or why it failed right under it. */
@Composable
private fun LineCard(state: EnrollUiState, vm: EnrollViewModel, onScan: () -> Unit) {
    val colors = AppTheme.colors
    FormCard(title = stringResource(R.string.enroll_step_paste)) {
        ScanButton(enabled = !state.busy, onScan = onScan)
        OutlinedTextField(
            value = state.text,
            onValueChange = vm::onText,
            label = { Text(stringResource(R.string.enroll_line_label)) },
            placeholder = { Text(stringResource(R.string.enroll_line_placeholder)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            minLines = 3,
            enabled = !state.busy,
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        // Only what was read from the line: the server itself answers on "Connect phone".
        if (state.error == null) {
            state.host?.let { Text(stringResource(R.string.enroll_line_ok, it), fontSize = 13.sp, color = colors.muted) }
        }
        if (state.needsName) {
            OutlinedTextField(
                value = state.name,
                onValueChange = vm::onName,
                label = { Text(stringResource(R.string.enroll_phone_name)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Why the scanner did not open: the camera was refused once (Android asks again), or for good. */
private enum class CameraRefused { ONCE, FOR_GOOD }

/**
 * "Scan QR code": opens the scanner, asking for the camera at this tap
 * when needed. Refused, a line says why it is wanted (tapping again asks
 * again); refused for good, a line with "Open settings". Pasting works
 * either way. Hidden on a phone without a camera.
 */
@Composable
private fun ScanButton(enabled: Boolean, onScan: () -> Unit) {
    val context = LocalContext.current
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return
    val colors = AppTheme.colors
    var refused by rememberSaveable { mutableStateOf<CameraRefused?>(null) }
    // Back from the settings: allowed there, the line goes.
    LifecycleResumeEffect(Unit) {
        if (cameraAllowed(context)) refused = null
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            refused = null
            onScan()
        } else {
            // After a refusal Android only skips the rationale when it will not ask any more.
            val activity = context.findActivity()
            val again = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            refused = if (again) CameraRefused.ONCE else CameraRefused.FOR_GOOD
        }
    }
    Button(
        onClick = { if (cameraAllowed(context)) onScan() else ask.launch(Manifest.permission.CAMERA) },
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Icon(ScanGlyph, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.enroll_scan), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
    }
    when (refused) {
        CameraRefused.ONCE -> Text(stringResource(R.string.camera_rationale), fontSize = 13.sp, color = colors.muted)
        CameraRefused.FOR_GOOD -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.camera_denied), fontSize = 13.sp, color = colors.muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { openAppSettings(context) }) {
                Text(stringResource(R.string.camera_open_settings), color = colors.accent)
            }
        }
        null -> Unit
    }
}

private fun cameraAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    } catch (_: ActivityNotFoundException) {
        // Nothing else to offer: the line stays, and pasting works.
    }
}
