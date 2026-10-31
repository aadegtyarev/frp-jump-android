package ru.adsrv.frpjump.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every text of the app is there in Russian, with the same placeholders and all the plural forms Russian needs. */
class TranslationsTest {
    private val english = StringResources("")
    private val russian = StringResources("-ru")

    @Test
    fun `every string and plurals has a Russian text`() {
        val strings = english.strings.keys - english.untranslatable - russian.strings.keys
        val plurals = english.plurals.keys - english.untranslatable - russian.plurals.keys
        assertTrue("not in values-ru: $strings $plurals", strings.isEmpty() && plurals.isEmpty())
    }

    @Test
    fun `Russian has nothing English does not, nor what must stay untranslated`() {
        val extra = (russian.strings.keys - english.strings.keys) + (russian.plurals.keys - english.plurals.keys)
        assertTrue("only in values-ru: $extra", extra.isEmpty())
        val translated = (russian.strings.keys + russian.plurals.keys).intersect(english.untranslatable)
        assertTrue("untranslatable, yet in values-ru: $translated", translated.isEmpty())
    }

    @Test
    fun `plurals have the forms each language needs`() {
        english.plurals.forEach { (name, forms) ->
            assertTrue("$name in English: ${forms.keys}", forms.keys.containsAll(listOf("one", "other")))
        }
        russian.plurals.forEach { (name, forms) ->
            assertTrue("$name in Russian: ${forms.keys}", forms.keys.containsAll(listOf("one", "few", "many", "other")))
        }
    }

    @Test
    fun `translations take the same arguments`() {
        russian.strings.forEach { (name, text) ->
            assertEquals(name, placeholders(english.strings.getValue(name)), placeholders(text))
        }
        russian.plurals.forEach { (name, forms) ->
            val expected = english.plurals.getValue(name).values.map(::placeholders).toSet()
            forms.forEach { (quantity, text) -> assertTrue("$name/$quantity", placeholders(text) in expected) }
        }
    }

    /** The format arguments a text uses, by position: "%1\$s %2\$d" and "%s %d" both as [1=s, 2=d]. */
    private fun placeholders(text: String): Map<Int, Char> {
        var next = 1
        return PLACEHOLDER.findAll(text).filter { it.value != "%%" }.associate { m ->
            val position = m.groupValues[1].toIntOrNull() ?: next++
            position to m.groupValues[2].single()
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(?:(\d+)\$)?([sdf%])""")
    }
}
