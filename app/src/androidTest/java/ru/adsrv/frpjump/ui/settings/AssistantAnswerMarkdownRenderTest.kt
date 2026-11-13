package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** The assistant's answer rendering on its own (tables, blocks), in this run's orientation, font scale and language. */
@RunWith(AndroidJUnit4::class)
class AssistantAnswerMarkdownRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        args.getString("chatLocale")?.let { lang ->
            val res = compose.activity.resources
            val config = Configuration(res.configuration).apply { setLocale(Locale(lang)) }
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            assertEquals(lang, res.configuration.locales[0].language)
        }
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    private fun show(answer: String, mode: ThemeMode = ThemeMode.LIGHT) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                FrpJumpTheme(mode = mode) {
                    Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) { SelectionContainer { AssistantAnswerMarkdown(answer) } }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("assistant_markdown_rendered").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private val table = SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)
    private fun layout(node: SemanticsNode): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(out)
        return out.single()
    }
    private fun cellTexts() = compose.onAllNodes(hasAnyAncestor(hasTestTag("assistant_table")) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()

    private val grid = "| Хост | Port | Status | Note |\n| --- | :-: | --: | --- |\n| alpha | 22 | up | first |\n| beta | 2222 | down | [l](https://example.test) |"

    private fun checkTable(mode: ThemeMode) {
        show("Table below:\n$grid\n\nAfter the table.", mode)
        compose.onAllNodesWithText("---", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("| Хост", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("assistant_table").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("After the table.").assertExists()
        // Row by row, as TalkBack walks the tree.
        val texts = cellTexts().map { it.config[SemanticsProperties.Text].joinToString("") }
        assertEquals(listOf("Хост", "Port", "Status", "Note", "alpha", "22", "up", "first", "beta", "2222", "down", "[l](https://example.test)"), texts)
        val info = compose.onNode(table).fetchSemanticsNode().config[SemanticsProperties.CollectionInfo]
        assertEquals(3, info.rowCount); assertEquals(4, info.columnCount)
        compose.onNodeWithText("[l](https://example.test)").assertHasNoClickAction()
        cellTexts().forEach { assertFalse(layout(it).hasVisualOverflow) }
    }

    @Test fun fourColumnTableRendersAfterATextLineLight() = checkTable(ThemeMode.LIGHT)
    @Test fun fourColumnTableRendersDark() = checkTable(ThemeMode.DARK)

    @Test fun wideTableScrollsInsideItsOwnAreaWithoutCuttingText() {
        val cells = (1..8).map { "Column $it with a long heading" }
        val row = (1..8).map { "value-$it that is long enough to wrap or widen" }
        fun line(items: List<String>) = items.joinToString(" | ", "| ", " |")
        show("Wide:\n\n${line(cells)}\n${line(cells.map { "---" })}\n${line(row)}\n\nTail")
        val root = compose.onRoot().getBoundsInRoot()
        val markdown = compose.onNodeWithTag("assistant_markdown_rendered").getBoundsInRoot()
        assertTrue("markdown must stay within the screen: $markdown in $root", markdown.right <= root.right + 0.5.dp)
        val scroll = compose.onNodeWithTag("assistant_table").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("a wide table must scroll", scroll.maxValue() > 0f)
        cellTexts().forEach { assertFalse(layout(it).hasVisualOverflow) }
        val all = cellTexts().map { it.config[SemanticsProperties.Text].joinToString("") }
        assertEquals(cells + row, all)
        val last = compose.onNodeWithText(row.last())
        last.performScrollTo().assertIsDisplayed()
        assertTrue(last.getBoundsInRoot().right <= root.right + 0.5.dp)
        compose.onNodeWithText("Tail").assertExists()
    }

    @Test fun headingListQuoteRuleAndCodeBlockRender() {
        show("# Big title\n\n1. First step\n2. Second step\n   - nested point\n\n> quoted words\n\n---\n\n```\nfn main() { call_something_quite_long_here(argument_one, argument_two, argument_three) }\n  indented\n```\n\nplain text")
        val title = compose.onNodeWithText("Big title").assertExists()
        compose.onAllNodesWithText("# Big title", substring = true).assertCountEquals(0)
        assertTrue(layout(title.fetchSemanticsNode()).layoutInput.style.fontSize.value > layout(compose.onNodeWithText("plain text").fetchSemanticsNode()).layoutInput.style.fontSize.value)
        compose.onAllNodesWithText("1.", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("2.", substring = true).assertCountEquals(1)
        compose.onNodeWithText("First step").assertExists()
        compose.onNodeWithText("nested point").assertExists()
        compose.onAllNodesWithText("- nested", substring = true).assertCountEquals(0)
        compose.onNodeWithText("quoted words").assertExists()
        compose.onAllNodesWithText("> quoted", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("---", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("```", substring = true).assertCountEquals(0)
        val code = compose.onNode(hasText("fn main()", substring = true) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)))
        code.assertExists()
        assertFalse(layout(code.fetchSemanticsNode()).hasVisualOverflow)
        compose.onNodeWithText("  indented", substring = true).assertExists()
    }
}
