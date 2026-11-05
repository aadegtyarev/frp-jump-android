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
        val wrapped = "before ``\n**literal**\n- item\n`` after **bold**"
        assertEquals("before ``\n**literal**\n- item\n`` after bold", formatAssistantAnswer(wrapped).text)
    }

    @Test fun `inline code retains trailing and repeated backslashes`() {
        listOf("C:\\", "one\\\\", "**x**\\", "🌍\\").forEach { payload ->
            val original = "before `" + payload + "` after **bold**"
            val result = formatAssistantAnswer(original)
            assertEquals("before " + payload + " after bold", result.text)
            assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && result.text.substring(it.start, it.end) == payload })
            assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        }
    }

    @Test fun `unsupported tick wrappers protect their complete payload`() {
        listOf("``**x** \\*y\\*``", "```**x**```", "``**x**`", "`**x**``",
            "``**x**````", "\\``**x**\\*y\\*``", "``**x**`` ``_y_``").forEach { literal ->
            val result = formatAssistantAnswer("**before** " + literal + " **after**")
            // Unequal runs conservatively protect the remainder, including the suffix.
            val expected = if (literal in listOf("``**x**`", "`**x**``", "``**x**````"))
                "before " + literal + " **after**" else "before " + literal + " after"
            assertEquals(expected, result.text)
            assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 })
        }
    }

    @Test fun `unsupported and mismatched fences protect body lists and escapes`() {
        listOf("````\n**x**\n- item\n\\*escaped\\*\n````", "`````lang\n*italics*\n+ item\n`````", "``\n**x**\n- item\n``", "`\n**x**\n- item\n`",
            "```lang\n**x**\n- item\n````", "````\n**x**\n```\n````").forEach {
            assertEquals(it, formatAssistantAnswer(it).text)
        }
        val result = formatAssistantAnswer("**before**\n````\n**x**\n- item\n````\n**after**")
        assertEquals("before\n````\n**x**\n- item\n````\nafter", result.text)
        assertEquals(2, result.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun `multiline unsupported html link and image keep body literal`() {
        listOf("<b>\n**x**\n- item\n\\*escape\\*\n</b>", "<b><b>\n**x**\n</b>\n*still literal*\n</b>",
            "[link](\n**y**\n- item\n(nested)\\*x\\*)", "![image](\n**y**\n+ item\n\\*x\\*)",
            "<b>\n**unfinished**", "[link](\n**unfinished**\n- item").forEach {
            assertEquals(it, formatAssistantAnswer(it).text)
        }
        assertEquals("[ordinary] before bold [link](url)", formatAssistantAnswer("[ordinary] before **bold** [link](url)").text)
        val result = formatAssistantAnswer("**before** <b>\n**x**\n</b> **after**")
        assertEquals("before <b>\n**x**\n</b> after", result.text)
        assertEquals(2, result.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun `quoted html attribute delimiters do not expose literal payload`() {
        val literals = listOf(
            "<img alt=\"/> \\*x\\* **y**\" />",
            "<img alt='/> \\*x\\* **y**' />",
            "<img alt=\"one > two / > **x**\" title='> \\*z\\*' />",
            "<div title=\"/> **attribute**\">\n**body**\n- item\n\\*escape\\*\n</div>",
            "<div title='/> **attribute**'>\n**body**\n- item\n</div>",
            "<div title=\"</div> >\"><div title='/> x'>**inner**</div> **body**</div>",
            "<img alt=\"unterminated > **literal**\n- item"
        )
        literals.forEachIndexed { index, literal ->
            val isUnclosed = index == literals.lastIndex
            val result = formatAssistantAnswer("**before** " + literal + " **after**")
            assertEquals("case $index", "before " + literal + if (isUnclosed) " **after**" else " after", result.text)
            assertEquals(if (isUnclosed) 1 else 2, result.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
        }
    }

    @Test fun `links and images keep opaque bracket bearing labels literal`() {
        val labels = listOf("`[`", "`]`", "`[[]`", "<span>[</span>", "<span>]</span>",
            "<span title=\"[ ] >\">**[**</span>", "[nested] `[`", "\\[escaped\\] `]`",
            "`[`\n**label**\n- item", "<span title=']'>\n[ **literal**\n</span>",
            "\\``[**literal**``", "``] **literal**``", "<span title=\"/> ]\">[</span>",
            "\\`[\\`", "\\`]\\`", "\\<span title='['/>",
            "\n```lang\n`\n] **literal**\n``\n[\n```\n")
        for (prefix in listOf("", "!")) for (label in labels) {
            val literal = prefix + "[" + label + "](https://example.test/(a)\\*x\\*)"
            val result = formatAssistantAnswer("**before** " + literal + " **after**")
            assertEquals(literal, "before " + literal + " after", result.text)
            assertEquals(literal, 2, result.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
            assertTrue(result.getStringAnnotations(0, result.length).isEmpty())
        }
    }

    @Test fun `malformed opaque labels and destination payload stay literal`() {
        for (prefix in listOf("", "!")) {
            val malformed = prefix + "[`[` <img title=\"unterminated ](url)\n- **body**"
            assertEquals(malformed, formatAssistantAnswer(malformed).text)
            val destinations = listOf("url \"title ) **literal**\"", "<span title=')'>**literal**</span>",
                "`)`**literal**", "url(foo\\)bar)")
            for (destination in destinations) {
                val literal = prefix + "[label](" + destination + ")"
                val rendered = formatAssistantAnswer("**before** " + literal + " **after**")
                assertEquals("before " + literal + " after", rendered.text)
                assertEquals(2, rendered.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
            }
        }
    }

    @Test fun `short tick-only lines are payload in supported triple fences`() {
        for (language in listOf("", "kotlin")) for (payload in listOf("`", "``", "  `  ", "\t``\t")) {
            val original = "**before**\n```" + language + "\n" + payload + "\n```\n**after**"
            val result = formatAssistantAnswer(original)
            assertEquals("before\n" + payload + "\nafter", result.text)
            assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && result.text.substring(it.start, it.end) == payload + "\n" })
            assertEquals(2, result.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
        }
        val mixed = "```lang\n`\n  ``  \n🌍 **literal**\n```"
        val result = formatAssistantAnswer(mixed)
        assertEquals("`\n  ``  \n🌍 **literal**\n", result.text)
        assertEquals(1, result.spanStyles.count { it.item.fontFamily == FontFamily.Monospace && it.start == 0 && it.end == result.length })
    }

    @Test fun `long text and repeated malformed openers remain complete`() {
        val text = "[".repeat(16000) + "\n" + "Привет 🌍 ".repeat(2000) + "\n" + "<".repeat(16000)
        assertEquals(text, formatAssistantAnswer(text).text)
    }
}
