package ru.adsrv.frpjump.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

/**
 * True while the app lock is engaged ([ru.adsrv.frpjump.MainActivity] provides it from `AppLock.locked`).
 * The screens stay composed under the lock screen, but a separate window (dialog, sheet, menu, popup)
 * is a window of its own, above the lock screen, with its own touches and keys.
 * Every such window of the app is made through the wrappers below, which leave it out while locked.
 * Their callers keep the state, so the window returns after unlock as it was. State declared inside a
 * window's content survives too if it is `rememberSaveable` (each wrapper keeps a saveable-state holder
 * outside the window and provides it inside the window, whose composition has its own registry); a plain `remember` there is lost while locked, so window content uses `rememberSaveable`.
 * Nothing else in `app/src/main` may use the platform window APIs (`LockedWindowsSourceScanTest` checks it).
 */
val LocalAppLocked = compositionLocalOf { false }


/** [androidx.compose.material3.AlertDialog], not composed while locked. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val holder = rememberSaveableStateHolder()
    if (LocalAppLocked.current) return
    // The dialog is its own window with its own saved-state registry: each slot is kept by the holder inside it.
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { holder.SaveableStateProvider("confirm", confirmButton) },
        modifier = modifier,
        dismissButton = dismissButton?.let { slot -> { holder.SaveableStateProvider("dismiss", slot) } },
        icon = icon?.let { slot -> { holder.SaveableStateProvider("icon", slot) } },
        title = title?.let { slot -> { holder.SaveableStateProvider("title", slot) } },
        text = text?.let { slot -> { holder.SaveableStateProvider("text", slot) } },
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/** [androidx.compose.ui.window.Dialog], not composed while locked. */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    if (LocalAppLocked.current) return
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        holder.SaveableStateProvider("content", content)
    }
}

/** [androidx.compose.material3.ModalBottomSheet], not composed while locked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    if (LocalAppLocked.current) return
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColorFor(containerColor),
    ) {
        holder.SaveableStateProvider("content") { content() }
    }
}

/** [androidx.compose.ui.window.Popup], not composed while locked. */
@Composable
fun AppPopup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    if (LocalAppLocked.current) return
    androidx.compose.ui.window.Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        holder.SaveableStateProvider("content", content)
    }
}

/** [androidx.compose.material3.DropdownMenu], not composed while locked (an open menu is simply closed by the lock). */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    if (LocalAppLocked.current) return
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
    ) {
        holder.SaveableStateProvider("content") { content() }
    }
}
