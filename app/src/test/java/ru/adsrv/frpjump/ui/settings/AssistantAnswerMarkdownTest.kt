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
}
