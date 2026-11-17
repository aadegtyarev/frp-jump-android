package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.SessionCount
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File

/** The connection card's "Assistant: no questions, for the tunnel's lifetime" line with Forget: shown only while the grant exists; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class ConnectionCardAssistantTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private val row = ConnectionRow("synthetic-profile", Connection("c1", "synthetic-device", 22, PortKind.SSH, customName = "Pi shell", user = "pi"), saved = true, tunnel = null, pending = null, error = null)
    private val actions = RowActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { false }, {}, {})

    private fun shot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(300); compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    @Test fun theLineAppearsWithTheGrantAndForgetRemovesItAndItsTargetAndLabelAreRight() {
        var granted by mutableStateOf(true)
        var forgotten = 0
        compose.setContent {
            FrpJumpTheme {
                ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions,
                    assistantGrant = granted, onForgetAssistant = { forgotten++; granted = false })
            }
        }
        compose.onNodeWithTag("connection_assistant_grant").assertIsDisplayed()
        compose.onNodeWithTag("connection_assistant_forget", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(text(R.string.connection_assistant_grant)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.connection_assistant_forget_description, "Pi shell")).assertIsDisplayed()
        shot("connection-card-assistant-grant")
        compose.onNodeWithTag("connection_assistant_forget").performClick()
        compose.waitForIdle()
        assertEquals(1, forgotten)
        compose.onNodeWithTag("connection_assistant_grant").assertDoesNotExist()
        shot("connection-card-assistant-forgotten")
    }

    @Test fun noLineWithoutAGrant() {
        compose.setContent { FrpJumpTheme { ConnectionCard(row, sshMethod = null, sessions = SessionCount(0, 0), menuOpen = false, onMenu = {}, actions = actions) } }
        compose.onNodeWithTag("connection_assistant_grant").assertDoesNotExist()
        compose.onNodeWithTag("connection_assistant_forget").assertDoesNotExist()
    }
}
