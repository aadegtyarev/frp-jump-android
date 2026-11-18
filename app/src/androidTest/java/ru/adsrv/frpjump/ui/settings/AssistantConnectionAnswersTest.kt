package ru.adsrv.frpjump.ui.settings

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.CloseCard
import ru.adsrv.frpjump.assistant.SkillCard
import ru.adsrv.frpjump.assistant.StopCard
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.ConfirmationTargetInfo
import ru.adsrv.frpjump.assistant.chat.PendingConfirmation
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File
import java.util.Locale

/**
 * The confirmation card's five answers (Allow and Decline, a caption "Allow and remember", then For session, For this connection and Always) in the
 * language, orientation and font scale the run asks for (the chat runner's arguments, as the chat screen test reads them); synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
class AssistantConnectionAnswersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val info = ConfirmationTargetInfo("Synthetic profile", Connection("c1", "synthetic-device", 22, PortKind.SSH, customName = "Pi shell", user = "pi"))
    private val answers = ArrayList<ConfirmationDecision>()
    private val both = setOf(AssistantToolId.SSH_COMMAND, AssistantToolId.TERMINAL_INPUT)

    @Before fun display() {
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val until = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, .01f)
    }

    private fun locale() = Locale.forLanguageTag(args.getString("chatLocale") ?: "en")
    private fun localized(): Context = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(locale()) })
    private fun text(id: Int, vararg format: Any): String = localized().getString(id, *format)
    private fun landscape() = compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun show(confirmation: PendingConfirmation, heightDp: Int? = null) {
        @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(Configuration(compose.activity.resources.configuration).apply { setLocale(locale()) }, compose.activity.resources.displayMetrics)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale()) }
            CompositionLocalProvider(LocalContext provides LocalContext.current.createConfigurationContext(configuration), LocalConfiguration provides configuration) {
                FrpJumpTheme {
                    androidx.compose.foundation.layout.Box(heightDp?.let { androidx.compose.ui.Modifier.fillMaxWidth().height(it.dp) } ?: androidx.compose.ui.Modifier.fillMaxSize()) {
                        ChatConfirmationCard(confirmation, onAnswer = { answers += it }, wide = landscape())
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun named() = show(PendingConfirmation(1, AssistantToolId.SSH_COMMAND, "uptime", GrantTarget("p", "c1"), info, sessionTools = both))

    private fun shot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(300); compose.waitForIdle()
        val tag = listOf(args.getString("chatLocale") ?: "en", args.getString("composerOrientation") ?: "portrait", "font" + (args.getString("composerFontScale") ?: "1.0")).joinToString("-")
        val image = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "e3-$name-$tag.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo() }
        return node.assertIsDisplayed()
    }
    /** A button draws 32 dp (more only when its label wraps) and keeps a touch target of at least 44 dp around it. */
    private fun assertTouchTarget(tag: String, node: SemanticsNodeInteraction) {
        val n = node.fetchSemanticsNode(); val d = compose.density.density
        val touch = n.touchBoundsInRoot.height / d; val drawn = n.boundsInRoot.height / d
        assertTrue("$tag: touch target $touch dp is at least 44", touch >= 44f - 0.5f)
        assertTrue("$tag: drawn height $drawn dp is 32 (or more when the label wraps)", drawn >= 32f - 0.5f && (drawn <= 32f + 0.5f || drawn > 36f))
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private val buttons = listOf("chat_confirm_allow" to R.string.confirm_allow, "chat_confirm_decline" to R.string.confirm_decline, "chat_confirm_session" to R.string.confirm_session,
        "chat_confirm_connection" to R.string.confirm_connection, "chat_confirm_always" to R.string.confirm_always)

    @Test fun fiveAnswersInTheOrderOfTheContractUnderTheirCaption() {
        named()
        for ((tag, _) in buttons) assertTouchTarget(tag, shown(tag))
        shown("chat_confirm_remember").assertTextContains(text(R.string.confirm_remember))
        val allow = bounds("chat_confirm_allow"); val decline = bounds("chat_confirm_decline"); val caption = bounds("chat_confirm_remember")
        val session = bounds("chat_confirm_session"); val connection = bounds("chat_confirm_connection"); val always = bounds("chat_confirm_always")
        // Row 1: Allow then Decline (Decline next to Allow); then the caption; then, in reading order, For session, For this connection, Always (last).
        assertTrue("Decline follows Allow: $allow $decline", decline.top > allow.top + 1f || decline.left > allow.left)
        assertTrue("the caption is under the first row", caption.top >= maxOf(allow.bottom, decline.bottom) - 1f)
        for ((name, b) in listOf("session" to session, "connection" to connection, "always" to always)) assertTrue("$name is under the caption", b.top >= caption.bottom - 1f)
        assertTrue("For this connection follows For session", connection.top > session.top + 1f || connection.left > session.left)
        assertTrue("Always is last", always.top > connection.top + 1f || always.left > connection.left)
        shot("card-five-answers")
    }

    @Test fun noLabelIsCutAndEveryButtonIsOnScreen() {
        named()
        val root = compose.onAllNodes(isRoot())[0].fetchSemanticsNode().boundsInRoot
        for ((tag, label) in buttons) {
            shown(tag)
            val b = bounds(tag)
            assertTrue("$tag inside the screen: $b root=$root", b.left >= root.left - 1f && b.right <= root.right + 1f)
            val textNode = compose.onNode(hasText(text(label)) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNode()
            val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.runOnIdle { textNode.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(out) }
            assertTrue("$tag: the label is cut (ellipsis)", (0 until out.first().lineCount).none { out.first().isLineEllipsized(it) })
            val t = textNode.boundsInRoot
            assertTrue("$tag: the label is inside its button: text=$t button=$b", t.left >= b.left - 1f && t.right <= b.right + 1f && t.top >= b.top - 1f && t.bottom <= b.bottom + 1f)
        }
    }

    @Test fun oneHintUnderTheRememberAnswersNamesBothTools() {
        named()
        // One hint for the three remembered answers, no per-answer hints and no spoken description on the buttons (the hint is read in its place).
        for (tag in listOf("chat_confirm_session_hint", "chat_confirm_connection_hint")) compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).assertCountEquals(0)
        shown("chat_confirm_remember_hint").assertTextEquals(text(R.string.confirm_remember_hint_both))
        for (tag in listOf("chat_confirm_session", "chat_confirm_connection", "chat_confirm_always"))
            assertTrue("$tag has no description of its own", !compose.onNodeWithTag(tag).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
    }

    @Test fun theHintOfTypingAloneAndOfCommandsAlone() {
        show(PendingConfirmation(1, AssistantToolId.SSH_COMMAND, "uptime", GrantTarget("p", "c1"), info, sessionTools = setOf(AssistantToolId.SSH_COMMAND)))
        shown("chat_confirm_remember_hint").assertTextEquals(text(R.string.confirm_remember_hint))
    }

    @Test fun theTitleNamesTheConnectionAndKeepsItsNameInOnePiece() {
        named()
        val title = compose.onNodeWithTag("chat_confirm_title", useUnmergedTree = true)
        title.assertTextEquals(text(R.string.confirm_title_in, "\u2068Pi shell\u2069"))
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val titleNode = title.fetchSemanticsNode()
        compose.runOnIdle { titleNode.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(out) }
        val layout = out.first(); val full = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount) assertTrue("a line starts and ends at a word boundary", layout.getLineStart(line).let { it == 0 || full[it - 1].isWhitespace() || full[it - 1] == '\u2069' })
    }

    @Test fun theAnswersMapToTheDecisionsAndOnlyAlwaysAsksAgain() {
        named()
        shown("chat_confirm_connection").performClick()
        compose.runOnIdle { assertEquals("no second confirmation", listOf(ConfirmationDecision.CONNECTION), answers) }
        shown("chat_confirm_session").performClick(); shown("chat_confirm_allow").performClick(); shown("chat_confirm_decline").performClick()
        compose.runOnIdle { assertEquals(listOf(ConfirmationDecision.CONNECTION, ConfirmationDecision.SESSION, ConfirmationDecision.ONCE, ConfirmationDecision.DENY), answers) }
        shown("chat_confirm_always").performClick()
        compose.onNodeWithTag("chat_always_dialog_title").assertIsDisplayed(); shot("card-always-dialog")
        compose.runOnIdle { assertEquals("Always still asks first", 4, answers.size) }
    }

    private fun threeAnswersOnly(what: String, card: PendingConfirmation) {
        show(card)
        compose.onNodeWithTag("chat_confirm_card").assertIsDisplayed()
        for (tag in listOf("chat_confirm_connection", "chat_confirm_remember", "chat_confirm_session", "chat_confirm_remember_hint")) {
            assertTrue("$what: $tag is not offered", compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        for (tag in listOf("chat_confirm_allow", "chat_confirm_always", "chat_confirm_decline")) assertTouchTarget(tag, shown(tag))
        shot("card-three-answers-" + what)
    }
    @Test fun unknownConnectionHasThreeAnswers() = threeAnswersOnly("unknown", PendingConfirmation(1, AssistantToolId.SSH_COMMAND, "uptime", null, null))
    @Test fun aConnectionWithNoNameHasThreeAnswers() = threeAnswersOnly("noname", PendingConfirmation(1, AssistantToolId.SSH_COMMAND, "uptime", GrantTarget("p", "c1"), null))
    @Test fun closeHasThreeAnswers() = threeAnswersOnly("close", PendingConfirmation(1, AssistantToolId.TERMINAL_CLOSE, "", null, null, close = CloseCard(4, "Synthetic profile", "pi", "synthetic-device", 2, null)))
    @Test fun stopHasThreeAnswers() = threeAnswersOnly("stop", PendingConfirmation(1, AssistantToolId.STOP_TUNNEL, "", GrantTarget("p", "c1"), info, stop = StopCard("Synthetic profile", "Pi shell", "synthetic-device", 22, 2)))
    @Test fun skillHasThreeAnswers() = threeAnswersOnly("skill", PendingConfirmation(1, AssistantToolId.SKILL_SAVE, "body", null, null, skill = SkillCard(SkillCard.Kind.SAVE, "synthetic-skill", "d", emptyList(), "body")))

    @Test fun theHintIsUnderTheRememberedAnswersAndStaysWithThem() {
        named()
        val hint = shown("chat_confirm_remember_hint"); hint.assertTextContains(text(R.string.confirm_remember_hint_both)); shown("chat_confirm_session")
        val caption = bounds("chat_confirm_remember"); val h = bounds("chat_confirm_remember_hint"); val session = bounds("chat_confirm_session"); val always = bounds("chat_confirm_always")
        assertTrue("the caption is above the remembered answers: $caption $session", session.top >= caption.bottom - 1f)
        assertTrue("the hint is under the remembered answers: $h $session $always", h.top >= maxOf(session.bottom, always.bottom) - 1f)
        // It is pinned with the buttons, not inside the scrolling section.
        // (In a window too short for it the whole card scrolls and the answers, hint included, are inside the scrolling section: nothing is pinned then.)
        if (!landscape()) assertTrue(compose.onAllNodes(hasTestTag("chat_confirm_remember_hint") and hasAnyAncestor(hasTestTag("chat_confirm_section")), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        shot("card-remember-hint")
    }

    /** In a window too short for the answers beside or under the section, the whole card scrolls: every answer can be brought on screen and none is cut. */
    private fun shortWindow(heightDp: Int) {
        show(PendingConfirmation(1, AssistantToolId.SSH_COMMAND, (1..40).joinToString("\n") { "echo synthetic-line-$it" }, GrantTarget("p", "c1"), info, sessionTools = both), heightDp)
        for ((tag, _) in buttons) assertTouchTarget(tag, shown(tag))
        shot("card-short-$heightDp")
    }
    @Test fun aShortWindow260dpScrollsTheWholeCard() = shortWindow(260)
    @Test fun aShortWindow360dpScrollsTheWholeCard() = shortWindow(360)
}
