package ru.adsrv.frpjump.assistant.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** What the owner is shown must be what runs: a string that cannot be encoded as it is shown is not a valid argument for any tool. */
class LoneSurrogateTest {
    private fun obj(vararg p: Pair<String, String>) = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })

    @Test fun `lone surrogates are detected and valid pairs are not`() {
        for (s in listOf("\uD800", "\uDC00", "a\uD800", "\uDC00b", "\uD800\uD800", "\uDC00\uD800", "rm -rf /\uD800\uD800\uD800")) assertTrue(s, hasLoneSurrogate(s))
        for (s in listOf("", "abc", "🌍", "x🌍y", "🌍🌍", "привет")) assertFalse(s, hasLoneSurrogate(s))
    }

    @Test fun `StrictArgs string and optionalString reject a lone surrogate`() {
        val args = StrictArgs(obj("a" to "x\uD800y", "ok" to "fine 🌍"))
        assertThrows(InvalidArguments::class.java) { args.string("a") }
        assertThrows(InvalidArguments::class.java) { args.optionalString("a", allowBlank = true) }
        assertEquals("fine 🌍", args.string("ok")); assertEquals("fine 🌍", args.optionalString("ok"))
    }
}
