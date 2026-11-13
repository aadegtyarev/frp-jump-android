package ru.adsrv.frpjump.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app lock leaves every window of the app out ([LocalAppLocked]), takes no touch in
 * their place, and brings each back, with what was typed, after unlock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class LockedWindowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var locked by mutableStateOf(false)
    private var dialog by mutableStateOf(false)
    private var sheet by mutableStateOf(false)
    private var menu by mutableStateOf(false)
    private var popup by mutableStateOf(false)
    private var plainDialog by mutableStateOf(false)
    private var inside by mutableStateOf(false)
    private var confirmed by mutableIntStateOf(0)
    private var dismissed by mutableIntStateOf(0)

    /** State kept in the screen's function beside the window, as the app's own dialogs keep it. */
    @Composable
    private fun DialogWithField() {
        var text by remember { mutableStateOf("") }
        if (dialog) AppAlertDialog(
            onDismissRequest = { dismissed++ },
            text = { TextField(text, { text = it }, Modifier.testTag("dialog_field")) },
            confirmButton = { TextButton(onClick = { confirmed++ }, Modifier.testTag("dialog_ok")) { Text("Confirm") } },
        )
    }

    @Composable
    private fun SheetWithField() {
        var text by remember { mutableStateOf("") }
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        if (sheet) AppModalBottomSheet(onDismissRequest = { dismissed++ }, sheetState = state) {
            TextField(text, { text = it }, Modifier.testTag("sheet_field"))
        }
    }

    /** The field's state is declared inside the window's content: it survives the lock only through the wrapper's holder. */
    @Composable
    private fun StateInsideContent() {
        if (inside) {
            AppAlertDialog(
                onDismissRequest = { dismissed++ },
                text = {
                    var text by rememberSaveable { mutableStateOf("") }
                    TextField(text, { text = it }, Modifier.testTag("inside_dialog_field"))
                },
                confirmButton = {},
            )
            AppModalBottomSheet(onDismissRequest = { dismissed++ }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                var text by rememberSaveable { mutableStateOf("") }
                TextField(text, { text = it }, Modifier.testTag("inside_sheet_field"))
            }
        }
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLocked provides locked) {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        DialogWithField()
                        SheetWithField()
                        StateInsideContent()
                        Box {
                            AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Menu item") }, onClick = { menu = false })
                            }
                        }
                        if (popup) AppPopup { Text("Popup text") }
                        if (plainDialog) AppDialog(onDismissRequest = { dismissed++ }) { Text("Plain dialog") }
                    }
                }
            }
        }
    }

    private fun windows(dialogs: Int, popups: Int) {
        compose.onAllNodes(isDialog()).assertCountEquals(dialogs)
        compose.onAllNodes(isPopup()).assertCountEquals(popups)
    }

    private fun tapOnScreen(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            automation.injectInputEvent(event, true)
            event.recycle()
        }
    }

    @Test fun windowsStayOutWhileLockedAndReturnAfterUnlock() {
        show()
        compose.runOnIdle { dialog = true; sheet = true; menu = true; popup = true; plainDialog = true }
        compose.waitForIdle()
        windows(dialogs = 3, popups = 2)
        compose.onNodeWithTag("dialog_field").performTextInput("typed in dialog")
        compose.onNodeWithTag("sheet_field").performTextInput("typed in sheet")
        val button = compose.onNodeWithTag("dialog_ok").fetchSemanticsNode()
        val where = button.layoutInfo.coordinates.positionOnScreen() + Offset(button.size.width / 2f, button.size.height / 2f)

        compose.runOnIdle { locked = true }
        compose.waitForIdle()
        windows(dialogs = 0, popups = 0)
        compose.onAllNodes(hasTestTag("dialog_ok")).assertCountEquals(0)
        compose.onAllNodes(hasText("Menu item")).assertCountEquals(0)
        compose.onAllNodes(hasText("Popup text")).assertCountEquals(0)
        tapOnScreen(where.x, where.y)
        compose.waitForIdle()
        assertEquals("a tap where the dialog's button was reached it", 0, confirmed)
        assertEquals(0, dismissed)

        compose.runOnIdle { locked = false }
        compose.waitForIdle()
        windows(dialogs = 3, popups = 2)
        compose.onNodeWithTag("dialog_field").assert(hasText("typed in dialog"))
        compose.onNodeWithTag("sheet_field").assert(hasText("typed in sheet"))
    }

    @Test fun nothingChangesWhileUnlocked() {
        show()
        compose.runOnIdle { dialog = true }
        compose.waitForIdle()
        windows(dialogs = 1, popups = 0)
        compose.onNodeWithTag("dialog_ok").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun stateDeclaredInsideTheContentSurvivesTheLock() {
        show()
        compose.runOnIdle { inside = true }
        compose.waitForIdle()
        compose.onNodeWithTag("inside_dialog_field").performTextInput("kept in dialog")
        compose.onNodeWithTag("inside_sheet_field").performTextInput("kept in sheet")
        compose.runOnIdle { locked = true }
        compose.waitForIdle()
        windows(dialogs = 0, popups = 0)
        compose.runOnIdle { locked = false }
        compose.waitForIdle()
        compose.onNodeWithTag("inside_dialog_field").assert(hasText("kept in dialog"))
        compose.onNodeWithTag("inside_sheet_field").assert(hasText("kept in sheet"))
    }
}
