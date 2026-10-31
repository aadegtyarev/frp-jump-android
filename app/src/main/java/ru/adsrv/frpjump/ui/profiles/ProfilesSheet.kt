package ru.adsrv.frpjump.ui.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.NoticeLayer

/**
 * The profile list as a bottom sheet: tap a profile to show it, "⋮" for
 * its rare actions, "Add profile" at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSheet(
    vm: ProfilesViewModel,
    onDismiss: () -> Unit,
    /** After a switch: the app may need another screen (a profile without credentials). */
    onSwitched: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (id: String) -> Unit,
    onForgotten: () -> Unit,
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val forget by vm.forget.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        // A message posted while the sheet is open shows on it, not under its scrim.
        NoticeLayer {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.profiles), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.profiles_hint), fontSize = 13.sp, color = colors.muted)
                }
                items.forEach { item ->
                    ProfileRow(
                        item = item,
                        forgetting = forget?.busy == true && forget?.id == item.id,
                        // One forget at a time, and only once it is known what it would remove.
                        canForget = forget?.busy != true && item.serverLoaded,
                        onClick = {
                            onDismiss()
                            if (!item.current) vm.switchTo(item.id, onSwitched)
                        },
                        onEdit = {
                            onDismiss()
                            onEdit(item.id)
                        },
                        onForget = { confirm = item.id },
                    )
                }
                AddProfileRow(onClick = {
                    onDismiss()
                    onAdd()
                })
            }
        }
    }
    confirm?.let { id ->
        items.firstOrNull { it.id == id }?.let { item ->
            ForgetProfileDialog(
                item = item,
                busyElsewhere = forget?.busy == true,
                onConfirm = {
                    // A failure goes to the device list: the sheet may be gone by then.
                    val started = vm.forget(id, failureAsNotice = true) {
                        onDismiss()
                        onForgotten()
                    }
                    if (started) confirm = null
                },
                onDismiss = { confirm = null },
            )
        }
    }
}

@Composable
private fun ProfileRow(
    item: ProfileItem,
    forgetting: Boolean,
    canForget: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = AppTheme.colors
    Column {
        Surface(
            onClick = onClick,
            enabled = !forgetting,
            shape = RoundedCornerShape(16.dp),
            color = if (item.current) colors.accentContainer.copy(alpha = 0.45f).compositeOver(colors.card) else colors.card,
            border = if (item.current) BorderStroke(1.5.dp, colors.accent) else BorderStroke(1.dp, colors.divider),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
        ) {
            Row(
                Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProfileTile(item.letter, item.color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Nothing until the server is read: no "not connected" flash.
                    val sub = when {
                        item.removed -> stringResource(R.string.profile_removed_sub)
                        item.server != null -> stringResource(R.string.profile_server, item.server.host, item.server.deviceName)
                        item.serverLoaded -> stringResource(R.string.profile_not_enrolled)
                        else -> null
                    }
                    sub?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = if (item.removed) colors.error else colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when {
                    item.removed -> Chip(stringResource(R.string.profile_removed_badge), colors.errorContainer, colors.onErrorContainer)
                    item.tunnels > 0 -> Chip(
                        pluralStringResource(R.plurals.tunnels, item.tunnels, item.tunnels),
                        colors.accentContainer,
                        colors.onAccentContainer,
                    )
                }
                if (item.current) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.profile_current), tint = colors.accent)
                }
                if (forgetting) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    ProfileMenu(canForget, onEdit, onForget)
                }
            }
        }
    }
}

/** "⋮": name and colour, forget. */
@Composable
private fun ProfileMenu(canForget: Boolean, onEdit: () -> Unit, onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = AppTheme.colors.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.name_and_color)) }, onClick = {
                open = false
                onEdit()
            })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.forget_profile), color = if (canForget) AppTheme.colors.error else AppTheme.colors.muted) },
                enabled = canForget,
                onClick = {
                    open = false
                    onForget()
                },
            )
        }
    }
}

@Composable
private fun AddProfileRow(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = colors.card,
        modifier = Modifier
            .fillMaxWidth()
            // Full accent: at least 3:1 against the sheet in both themes (see the palette test).
            .dashedBorder(colors.accent, 16.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(colors.accentContainer.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent)
            }
            Text(stringResource(R.string.add_profile), color = colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

/**
 * Says what forgetting [item] does before it is done. While another
 * forget runs ([busyElsewhere]) it says so and "Forget" waits.
 */
@Composable
fun ForgetProfileDialog(item: ProfileItem, busyElsewhere: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val server = item.server
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forget_title, item.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        server == null -> stringResource(R.string.forget_text_local)
                        item.removed -> stringResource(R.string.forget_text_removed, server.host)
                        else -> stringResource(R.string.forget_text, server.deviceName, server.host)
                    },
                )
                if (busyElsewhere) Text(stringResource(R.string.forget_busy), color = AppTheme.colors.warning)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busyElsewhere) {
                Text(stringResource(R.string.forget), color = if (busyElsewhere) AppTheme.colors.muted else AppTheme.colors.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
