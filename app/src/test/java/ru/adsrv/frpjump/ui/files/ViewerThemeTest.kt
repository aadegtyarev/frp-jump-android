package ru.adsrv.frpjump.ui.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.ui.DarkPalette
import ru.adsrv.frpjump.ui.LightPalette

/** The viewer's TextMate theme is the palette's colours, in VS Code's theme format. */
class ViewerThemeTest {
    @Test
    fun `the theme carries the ground, the ink and every rule in the palette's colours`() {
        for (p in listOf(LightPalette, DarkPalette)) {
            val theme = Json.parseToJsonElement(viewerThemeJson(p, "t")).jsonObject
            assertEquals("t", theme.getValue("name").jsonPrimitive.content)
            assertEquals(hex(p.card), theme.getValue("colors").jsonObject.getValue("editor.background").jsonPrimitive.content)
            val rules = theme.getValue("tokenColors").jsonArray.map { it.jsonObject }
            // The first rule, without a scope, is the default text colour.
            assertEquals(hex(p.ink), rules.first().getValue("settings").jsonObject.getValue("foreground").jsonPrimitive.content)
            assertEquals(SYNTAX_RULES.size, rules.size - 1)
            SYNTAX_RULES.zip(rules.drop(1)).forEach { (rule, json) ->
                assertEquals(rule.scopes, json.getValue("scope").jsonArray.map { it.jsonPrimitive.content })
                val settings = json.getValue("settings").jsonObject
                assertEquals(hex(ruleColour(rule.colour(p), p.ink)), settings.getValue("foreground").jsonPrimitive.content)
                assertEquals(rule.style, settings.getValue("fontStyle").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `a rule in the default ink is written one step off, so it overrides an outer scope`() {
        assertEquals(0xFF14201DL, ruleColour(0xFF14201CL, 0xFF14201CL))
        assertEquals(0xFF0B6E5FL, ruleColour(0xFF0B6E5FL, 0xFF14201CL))
    }

    @Test
    fun `colours are written as RRGGBB without alpha`() {
        assertEquals("#0B6E5F", hex(0xFF0B6E5F))
        assertEquals("#000000", hex(0xFF000000))
    }
}
