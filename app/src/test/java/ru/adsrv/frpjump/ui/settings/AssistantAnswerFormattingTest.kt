package ru.adsrv.frpjump.ui.settings

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class AssistantAnswerFormattingTest {
    @Test fun `bold italic variants and nested styles remove paired delimiters`() {
        val result = formatAssistantAnswer("**жирный** __bold__ *курсив* _italic_ **bold *nested* end**")
        assertEquals("жирный bold курсив italic bold nested end", result.text)
        assertEquals(3, result.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
        assertEquals(3, result.spanStyles.count { it.item.fontStyle == FontStyle.Italic })
        val nested = result.text.indexOf("nested")
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start <= nested && it.end >= nested + 6 })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.start == nested && it.end == nested + 6 })
    }

    @Test fun `list variants preserve order indentation and multiline text`() {
        val text = "- one\n+ два\n  *  three\n1. first\n2. second\nordinary"
        assertEquals("• one\n• два\n  • three\n1. first\n2. second\nordinary", formatAssistantAnswer(text).text)
    }

    @Test fun `inline and fenced code are monospace and keep whitespace and markers`() {
        val text = "before `  x *y*  ` after\n```kotlin\n  val x = 1\n\t**literal**\n```\nlast"
        val result = formatAssistantAnswer(text)
        assertEquals("before   x *y*   after\n  val x = 1\n\t**literal**\nlast", result.text)
        assertEquals(2, result.spanStyles.count { it.item.fontFamily == FontFamily.Monospace })
        assertEquals("https://x/y_a_b", formatAssistantAnswer("`https://x/y_a_b`").text)
        assertEquals("x < y & https://x/a_b", formatAssistantAnswer("`x < y & https://x/a_b`").text)
        assertEquals("\n", formatAssistantAnswer("```\n```\n").text)
    }

    @Test fun `unsupported html links images and urls stay literal and inert`() {
        val text = "<b>**hello**</b> [**link**](https://example.test/a_b) ![pic](https://example.test/(x)**v**) https://example.test/a_b_c"
        val result = formatAssistantAnswer(text)
        assertEquals(text, result.text)
        assertTrue(result.getStringAnnotations(0, result.length).isEmpty())
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test fun `malformed delimiters and fences remain readable`() {
        listOf("**unfinished", "text * dangling", "_unclosed", "`unclosed", "```lang\n  x\n", "[bad](no end **still literal**", "<tag **unfinished**", "my_file_name").forEach {
            assertEquals(it, formatAssistantAnswer(it).text)
        }
    }

    @Test fun `escaping literals and adjacent plain unicode do not modify input`() {
        val text = "\\*not italic\\* \\`code\\` \\\\ Мир 🌍 e\u0301"
        assertEquals("*not italic* `code` \\ Мир 🌍 e\u0301", formatAssistantAnswer(text).text)
        assertEquals("\\*not italic\\* \\`code\\` \\\\ Мир 🌍 e\u0301", text)
    }

    @Test fun `unsupported tick runs and mismatched fences stay literal`() {
        val literals = listOf("``a``", "``a`", "`a``", "```inline```", "`a``b`", "``",
            "````lang\n  x\n````", "```lang\n  x\n````", "````\nx\n```")
        literals.forEach { assertEquals(it, formatAssistantAnswer(it).text) }
        assertEquals("one then ``two`` and three", formatAssistantAnswer("`one` then ``two`` and `three`").text)
        assertEquals("`escaped`` literal", formatAssistantAnswer("\\`escaped`` literal").text)
    }

    @Test fun `long text and repeated malformed openers remain complete`() {
        val text = "[".repeat(16000) + "\n" + "Привет 🌍 ".repeat(2000) + "\n" + "<".repeat(16000)
        assertEquals(text, formatAssistantAnswer(text).text)
    }
}
