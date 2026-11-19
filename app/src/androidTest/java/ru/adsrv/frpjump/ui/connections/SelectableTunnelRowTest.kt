package ru.adsrv.frpjump.ui.connections

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.adsrv.frpjump.ui.FrpJumpTheme

class SelectableTunnelRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectingExposesOnlyCheckedTargetAndNeverInvokesCardAction() {
        var checked by mutableStateOf(false)
        var invoked = 0
        compose.setContent {
            FrpJumpTheme {
                SelectableTunnelRow("SSH · router · Work", selecting = true, checked = checked, onToggle = { checked = !checked }) {
                    Button(onClick = { invoked++ }) { Text("Underlying action") }
                }
            }
        }
        compose.onNodeWithText("Underlying action").assertDoesNotExist()
        compose.onNodeWithContentDescription("SSH · router · Work").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(0, invoked) }
    }
}
