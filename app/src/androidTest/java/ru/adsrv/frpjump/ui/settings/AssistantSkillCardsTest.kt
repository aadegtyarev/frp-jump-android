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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.SkillCard
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.PendingConfirmation
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File

/** The owner's side of the assistant's skills: the save, replace and delete questions, showing the whole skill as plain text; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class AssistantSkillCardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun shot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(300); compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private val body = (1..30).joinToString("\n") { "Step $it: <b>run</b> **this** `now`" }

    @Test fun savingASkillShowsItsNameDescriptionRequirementsAndTheWholeTextAsPlainText() {
        val answers = ArrayList<ConfirmationDecision>()
        val card = SkillCard(SkillCard.Kind.SAVE, "restart-nginx", "Use when nginx must restart.", listOf("ssh_command", "terminal_input"), body)
        compose.setContent { FrpJumpTheme { ChatConfirmationCard(PendingConfirmation(1, AssistantToolId.SKILL_SAVE, body, null, null, skill = card), onAnswer = { answers += it }) } }
        compose.onNodeWithTag("chat_confirm_card").assertIsDisplayed()
        compose.onNodeWithTag("chat_confirm_tool", useUnmergedTree = true).assertTextEquals(text(R.string.tool_skill_save_receipt))
        compose.onNodeWithTag("chat_confirm_target", useUnmergedTree = true).assertTextEquals("restart-nginx")
        compose.onNodeWithTag("chat_confirm_skill_description", useUnmergedTree = true).assertTextContains("Use when nginx must restart.", substring = true)
        compose.onNodeWithTag("chat_confirm_skill_requires", useUnmergedTree = true).assertTextContains("ssh_command, terminal_input", substring = true)
        // The text is shown as it is: markup characters are not rendered, and the box scrolls (30 lines).
        compose.onNodeWithTag("chat_confirm_command", useUnmergedTree = true).assertTextContains("<b>run</b> **this**", substring = true)
        compose.onNodeWithTag("chat_confirm_text_label", useUnmergedTree = true).assertTextEquals(text(R.string.confirm_skill_text))
        compose.onNodeWithTag("chat_confirm_skill_old_toggle").assertDoesNotExist()
        compose.onNodeWithTag("chat_confirm_session").assertDoesNotExist()
        shot("card-skill-save")
        compose.onNodeWithTag("chat_confirm_allow").performClick()
        compose.runOnIdle { assertEquals(listOf(ConfirmationDecision.ONCE), answers) }
    }

    @Test fun replacingASkillSaysSoAndKeepsTheOldTextReachable() {
        val card = SkillCard(SkillCard.Kind.REPLACE, "restart-nginx", "Use when nginx must restart.", emptyList(), "new text", oldBody = "old text that was there")
        compose.setContent { FrpJumpTheme { ChatConfirmationCard(PendingConfirmation(1, AssistantToolId.SKILL_SAVE, "new text", null, null, skill = card), onAnswer = {}) } }
        compose.onNodeWithTag("chat_confirm_skill_requires", useUnmergedTree = true).assertTextContains(text(R.string.confirm_skill_requires_none), substring = true)
        compose.onNodeWithTag("chat_confirm_skill_old").assertDoesNotExist()
        compose.onNodeWithTag("chat_confirm_skill_old_toggle").performClick()
        compose.onNodeWithTag("chat_confirm_skill_old", useUnmergedTree = true).assertTextEquals("old text that was there")
        shot("card-skill-replace")
        compose.onNodeWithTag("chat_confirm_skill_old_toggle").performClick()
        compose.onNodeWithTag("chat_confirm_skill_old").assertDoesNotExist()
    }

    @Test fun deletingASkillShowsOnlyItsNameAndDescription() {
        val answers = ArrayList<ConfirmationDecision>()
        val card = SkillCard(SkillCard.Kind.DELETE, "restart-nginx", "Use when nginx must restart.", emptyList(), "the text is not shown")
        compose.setContent { FrpJumpTheme { ChatConfirmationCard(PendingConfirmation(1, AssistantToolId.SKILL_DELETE, "", null, null, skill = card), onAnswer = { answers += it }) } }
        compose.onNodeWithTag("chat_confirm_target", useUnmergedTree = true).assertTextEquals("restart-nginx")
        compose.onNodeWithTag("chat_confirm_skill_description", useUnmergedTree = true).assertTextContains("Use when nginx must restart.", substring = true)
        compose.onNodeWithTag("chat_confirm_command_box").assertDoesNotExist()
        shot("card-skill-delete")
        compose.onNodeWithTag("chat_confirm_decline").performClick()
        compose.runOnIdle { assertEquals(listOf(ConfirmationDecision.DENY), answers) }
    }
}
