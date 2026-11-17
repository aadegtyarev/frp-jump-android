package ru.adsrv.frpjump.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.chat.ConfirmationTargetInfo
import ru.adsrv.frpjump.assistant.chat.PendingConfirmation
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** The three For session hints on the real card, by what the grant would cover; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class AssistantSessionHintCardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = compose.activity.getString(id)
    private val info = ConfirmationTargetInfo("Synthetic profile", Connection("c1", "synthetic-device", 22, PortKind.SSH, customName = "Pi shell", user = "pi"))

    private fun hintFor(tools: Set<AssistantToolId>, expected: Int) {
        compose.setContent { FrpJumpTheme { ChatConfirmationCard(PendingConfirmation(1, AssistantToolId.SSH_COMMAND, "uptime", GrantTarget("p", "c1"), info, sessionTools = tools), onAnswer = {}) } }
        compose.onNodeWithTag("chat_confirm_session_hint", useUnmergedTree = true).assertIsDisplayed().assertTextContains(text(expected), substring = true)
    }

    @Test fun commandsOnly() = hintFor(setOf(AssistantToolId.SSH_COMMAND), R.string.confirm_session_hint)
    @Test fun typingOnly() = hintFor(setOf(AssistantToolId.TERMINAL_INPUT), R.string.confirm_input_session_hint)
    @Test fun both() = hintFor(setOf(AssistantToolId.SSH_COMMAND, AssistantToolId.TERMINAL_INPUT), R.string.confirm_session_hint_both)
}
