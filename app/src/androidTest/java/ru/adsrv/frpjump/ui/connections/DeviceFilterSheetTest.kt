package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.adsrv.frpjump.connections.DeviceGroup
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File

class DeviceFilterSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun identicalNamesUseProfileIdentityAndSearch() {
        val devices = listOf(DeviceGroup("work", "router", null, emptyList()), DeviceGroup("home", "router", null, emptyList()))
        var scope by mutableStateOf(listOf<String>())
        compose.setContent {
            FrpJumpTheme {
                DeviceFilterSheet(devices, mapOf("work" to "Work", "home" to "Home"), scope, onToggle = {
                    scope = if (inDeviceScope(scope, it) && scope.isNotEmpty()) emptyList() else listOf(it.profile, it.name)
                }, onClear = { scope = emptyList() }, onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription("router · Work").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("router · Home").assertIsOff()
        compose.runOnIdle { assertEquals(listOf("work", "router"), scope) }
        compose.onNodeWithTag("tunnels_filter_search").performTextInput("Home")
        compose.onNodeWithText("Work").assertDoesNotExist()
        compose.onNodeWithContentDescription("router · Home").assertIsOff()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
            File(compose.activity.getExternalFilesDir(null), "tunnels-filter-sheet.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }
}
