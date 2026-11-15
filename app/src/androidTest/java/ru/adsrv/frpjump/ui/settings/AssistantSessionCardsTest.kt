package ru.adsrv.frpjump.ui.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.CloseCard
import ru.adsrv.frpjump.assistant.OwnerAsk
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.PendingConfirmation
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.connections.AssistantLine
import ru.adsrv.frpjump.ui.connections.AssistantRow
import java.io.File

/** The owner's side of the assistant's own sessions: the close question, the "your input is needed" card and the Sessions group's row; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class AssistantSessionCardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun shot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(300); compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    @Test fun closingASessionAsksWithItsIdentityAndWhatStopsAndOffersNoSessionGrant() {
        val answers = ArrayList<ConfirmationDecision>()
        val card = CloseCard(4, "Synthetic profile", "pi", "synthetic-device", 2, null)
        compose.setContent { FrpJumpTheme { ChatConfirmationCard(PendingConfirmation(1, AssistantToolId.TERMINAL_CLOSE, "", null, null, close = card), onAnswer = { answers += it }) } }
        compose.onNodeWithTag("chat_confirm_card").assertIsDisplayed()
        compose.onNodeWithTag("chat_confirm_tool", useUnmergedTree = true).assertTextEquals(text(R.string.tool_terminal_close_receipt))
        compose.onNodeWithTag("chat_confirm_target", useUnmergedTree = true).assertTextEquals(text(R.string.chat_detail_tab_named, "Synthetic profile", "pi", "synthetic-device", 2))
        compose.onNodeWithTag("chat_confirm_close_warning", useUnmergedTree = true).assertTextEquals(text(R.string.confirm_close_warning))
        // The four answers except "For session" (there is no target to grant), and no command box.
        for (tag in listOf("chat_confirm_allow", "chat_confirm_always", "chat_confirm_decline")) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.onNodeWithTag("chat_confirm_session").assertDoesNotExist()
        compose.onNodeWithTag("chat_confirm_command_box").assertDoesNotExist()
        shot("card-terminal-close")
        compose.onNodeWithTag("chat_confirm_allow").performClick()
        compose.runOnIdle { assertEquals(listOf(ConfirmationDecision.ONCE), answers) }
    }

    @Test fun theOwnerAskShowsWhoWhereAndWhyAsPlainTextAndOpenGivesTheSession() {
        val opened = ArrayList<Int>(); val dismissed = ArrayList<Int>()
        // The model's reason is text, never markup: what looks like markup is shown as it is.
        val ask = OwnerAsk(4, "password for **sudo** <b>now</b>", "Synthetic profile", "pi", "synthetic-device", 2, "deploy")
        compose.setContent { FrpJumpTheme { OwnerAskCard(ask, onOpen = { opened += ask.id }, onDismiss = { dismissed += ask.id }) } }
        compose.onNodeWithTag("chat_ask_title").assertTextEquals(text(R.string.assistant_ask_title))
        compose.onNodeWithTag("chat_ask_identity", useUnmergedTree = true).assertTextContains("pi@synthetic-device", substring = true)
        compose.onNodeWithTag("chat_ask_reason").assertTextEquals("password for **sudo** <b>now</b>")
        compose.onNodeWithTag("chat_ask_open").assertTextEquals(text(R.string.assistant_ask_open))
        shot("card-owner-ask")
        compose.onNodeWithTag("chat_ask_open").performClick()
        compose.runOnIdle { assertEquals(listOf(4), opened); assertEquals(listOf(4), dismissed) }
    }

    @Test fun anAssistantRowInSessionsOffersOpenAndCloseAndSaysDetachedWhenNotAttached() {
        val log = ArrayList<String>()
        compose.setContent { FrpJumpTheme { AssistantLine(AssistantRow(9, "pi@synthetic-device", null), onOpen = { log += "open" }, onClose = { log += "close" }) } }
        compose.onNodeWithTag("sessions_assistant_9").assertIsDisplayed().assertTextContains(text(R.string.terminal_state_detached), substring = true)
        compose.onNodeWithTag("sessions_assistant_open_9").assertTextEquals(text(R.string.sessions_assistant_open)).performClick()
        compose.onNodeWithTag("sessions_assistant_close_9").assertTextEquals(text(R.string.sessions_assistant_close)).performClick()
        compose.runOnIdle { assertTrue(log.toString(), log == listOf("open", "close")) }
        shot("sessions-assistant-row")
    }
}
