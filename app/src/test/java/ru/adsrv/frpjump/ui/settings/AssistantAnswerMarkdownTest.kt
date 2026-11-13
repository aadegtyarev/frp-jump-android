package ru.adsrv.frpjump.ui.settings

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class AssistantAnswerMarkdownTest {
    private fun parse(text: String) = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    private fun node(root: ASTNode, type: org.intellij.markdown.IElementType): ASTNode =
        if (root.type == type) root else root.children.firstNotNullOf { child ->
            runCatching { node(child, type) }.getOrNull()
        }

    @Test fun `library retains bold italic and ordinary text styles`() {
        val source = "A **bold** year and *quiet* detail."
        val result = buildAnnotatedString {
            buildMarkdownAnnotatedString(source, parse(source), DefaultAnnotatorSettings(
                TextLinkStyles(), SpanStyle(), assistantMarkdownAnnotator,
            ))
        }
        assertEquals("A bold year and quiet detail.", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && result.text.substring(it.start, it.end) == "bold" })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic && result.text.substring(it.start, it.end) == "quiet" })
    }

    @Test fun `inline code preserves backslashes and whitespace with mono style`() {
        val text = "Use `C:\\` and ``  x  ``."
        val result = buildAnnotatedString {
            buildMarkdownAnnotatedString(text, parse(text), DefaultAnnotatorSettings(
                TextLinkStyles(), SpanStyle(), assistantMarkdownAnnotator,
            ))
        }
        assertEquals("Use C:\\ and   x  .", result.text)
        assertEquals(2, result.spanStyles.count { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
    }

    @Test fun `fenced code keeps indentation backslashes and tick payload`() {
        for (language in listOf("", "kotlin")) {
            val payload = "  C:\\\n    next\n`\n``\n"
            val source = "```" + language + "\n" + payload + "```"
            assertEquals(payload, assistantFenceText(source, node(parse(source), MarkdownElementTypes.CODE_FENCE)))
            assertEquals("```" + language + "\n" + payload + "```", source)
        }
    }

    @Test fun `long multiline content remains complete in a rendered paragraph`() {
        val source = (1..120).joinToString("\n") { "Line $it — длинный synthetic answer 🌍" }
        val result = buildAnnotatedString {
            buildMarkdownAnnotatedString(source, parse(source), DefaultAnnotatorSettings(
                TextLinkStyles(), SpanStyle(), assistantMarkdownAnnotator,
            ))
        }
        assertEquals(source, result.text)
    }

    @Test fun `reference links and autolinks never create link annotations`() {
        val source = "[label][id] ![pic][id] <https://example.test> <person@example.test>\n\n[id]: https://example.test"
        val result = buildAnnotatedString {
            buildMarkdownAnnotatedString(source, parse(source), DefaultAnnotatorSettings(
                TextLinkStyles(), SpanStyle(), assistantMarkdownAnnotator,
            ))
        }
        assertTrue(result.text.contains("[label][id]"))
        assertTrue(result.text.contains("![pic][id]"))
        assertTrue(result.text.contains("<https://example.test>"))
        assertTrue(result.text.contains("<person@example.test>"))
        assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
    }

    @Test fun `links images html and autolinks are plain inert text`() {
        val source = "[link](https://example.test) ![pic](https://example.test/x) <b>x</b> https://example.test"
        val result = buildAnnotatedString {
            buildMarkdownAnnotatedString(source, parse(source), DefaultAnnotatorSettings(
                TextLinkStyles(), SpanStyle(), assistantMarkdownAnnotator,
            ))
        }
        assertEquals(source, result.text)
        assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
    }

    private val grid = "| a | b | c | d |\n| --- | :-: | --: | --- |\n| 1 | 2 | 3 | 4 |\n| x | y |"

    @Test fun `a table after a text line gets one blank line so the library parses it`() {
        val source = "Here it is:\n$grid\nTail"
        val shown = assistantDisplayMarkdown(source)
        assertEquals("Here it is:\n\n$grid\nTail", shown)
        assertEquals(1, shown.length - source.length)
        assertNotNull(node(parse(shown), org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE))
        assertTrue(runCatching { node(parse(source), org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE) }.isFailure)
    }

    @Test fun `display copy leaves other text, fenced pipes and ready tables unchanged`() {
        val ready = "Intro\n\n$grid"
        val fenced = "```\nnote\n$grid\n```"
        val tilde = "~~~\nnote\n$grid\n~~~"
        val columns = "text\n| a | b |\n| --- |\n| 1 | 2 |"
        for (text in listOf("", "no pipes\n--- \nhere", ready, fenced, tilde, columns, "a | b\nnot a delimiter"))
            assertSame(text, assistantDisplayMarkdown(text))
        assertEquals("one\n\nA | B\n--|--\n1 | 2", assistantDisplayMarkdown("one\nA | B\n--|--\n1 | 2"))
        assertEquals("x\r\n\n| a |\r\n|---|\r\n| 1 |", assistantDisplayMarkdown("x\r\n| a |\r\n|---|\r\n| 1 |"))
    }

    @Test fun `table model keeps rows, pads short rows and reads alignment`() {
        val source = grid
        val table = node(parse(source), org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE)
        val model = assistantTableModel(source, table)
        assertEquals(4, model.columns)
        assertEquals(2, model.rows.size)
        assertTrue(model.rows.all { it.size == 4 })
        assertEquals(listOf("a", "b", "c", "d"), model.header.map { source.substring(it!!.startOffset, it.endOffset).trim() })
        assertEquals(listOf("1", "2", "3", "4"), model.rows[0].map { source.substring(it!!.startOffset, it.endOffset).trim() })
        assertNull(model.rows[1][2]); assertNull(model.rows[1][3])
        assertEquals(
            listOf(AssistantColumnAlignment.START, AssistantColumnAlignment.CENTER, AssistantColumnAlignment.END, AssistantColumnAlignment.START),
            model.alignments,
        )
    }
}
