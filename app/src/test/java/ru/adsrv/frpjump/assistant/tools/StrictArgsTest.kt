package ru.adsrv.frpjump.assistant.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StrictArgsTest {
    private fun args(vararg pairs: Pair<String, JsonElement>) = StrictArgs(JsonObject(mapOf(*pairs)))
    private fun rejects(block: () -> Unit) = try { block(); fail("must be rejected") } catch (_: InvalidArguments) { }
    private fun num(text: String) = Json.parseToJsonElement(text)

    @Test fun `exact and allowed key sets`() {
        args("a" to JsonPrimitive("x")).exactKeys("a")
        rejects { args("a" to JsonPrimitive("x")).exactKeys("a", "b") }
        rejects { args("a" to JsonPrimitive("x"), "b" to JsonPrimitive("y")).exactKeys("a") }
        rejects { args().exactKeys("a") }
        args().onlyKeys("a", "b")
        args("a" to JsonPrimitive("x")).onlyKeys("a", "b")
        rejects { args("c" to JsonPrimitive("x")).onlyKeys("a", "b") }
    }

    @Test fun `strings accept only nonblank JSON strings within the UTF-16 limit`() {
        assertEquals("x", args("a" to JsonPrimitive("x")).string("a", 1))
        assertEquals(" x ", args("a" to JsonPrimitive(" x ")).string("a"))
        rejects { args("a" to JsonPrimitive("xy")).string("a", 1) }
        rejects { args("a" to JsonPrimitive("")).string("a") }
        rejects { args("a" to JsonPrimitive(" \n")).string("a") }
        assertEquals(" ", args("a" to JsonPrimitive(" ")).string("a", allowBlank = true))
        // One emoji is two UTF-16 units.
        rejects { args("a" to JsonPrimitive("😀")).string("a", 1) }
        assertEquals("😀", args("a" to JsonPrimitive("😀")).string("a", 2))
        for (wrong in listOf(num("5"), num("5.0"), JsonPrimitive(true), JsonNull, buildJsonObject { }, buildJsonArray { })) {
            rejects { args("a" to wrong).string("a") }
            rejects { args("a" to wrong).optionalString("a") }
        }
        rejects { args().string("a") }
        assertNull(args().optionalString("a"))
        assertEquals("", args("a" to JsonPrimitive("")).optionalString("a", allowBlank = true))
        rejects { args("a" to JsonPrimitive(" ")).optionalString("a") }
    }

    @Test fun `integers are non-string JSON numbers that fit Int and the minimum`() {
        assertEquals(5, args("n" to num("5")).optionalInt("n", min = 0))
        assertEquals(0, args("n" to num("0")).optionalInt("n", min = 0))
        assertEquals(Int.MAX_VALUE, args("n" to num("2147483647")).optionalInt("n", min = 0))
        assertNull(args().optionalInt("n", min = 0))
        for (wrong in listOf(JsonPrimitive("5"), num("5.0"), num("5.5"), JsonPrimitive(true), JsonNull, num("2147483648"), num("-1"),
            buildJsonObject { }, buildJsonArray { add(JsonPrimitive(1)) })) {
            rejects { args("n" to wrong).optionalInt("n", min = 0) }
        }
        assertEquals(-1, args("n" to num("-1")).optionalInt("n"))
    }

    /**
     * Pre-F2 behavior kept deliberately: `intOrNull` on an unquoted JSON number is lenient (exponents, leading zeros,
     * even malformed exponents). Inputs are parsed from raw arguments JSON like the provider does. Any change here must be intentional.
     */
    @Test fun `offset leniency of unquoted numbers is pinned as before F2`() {
        fun offset(raw: String) = try {
            args("n" to Json.parseToJsonElement("{\"n\":$raw}").jsonObject.getValue("n")).optionalInt("n", min = 0)
        } catch (_: InvalidArguments) { null }
        val accepted = mapOf("1e2" to 100, "1E2" to 100, "10e-1" to 1, "05" to 5, "2e9" to 2_000_000_000, "1e" to 1, "1e+" to 1, "1e-" to 1, "-0" to 0)
        for ((raw, expected) in accepted) assertEquals(raw, expected, offset(raw))
        for (raw in listOf("+5", "0x10", "1.0e0", "1_0", "\"5\"", "2147483648", "-1")) assertNull(raw, offset(raw))
    }
}
