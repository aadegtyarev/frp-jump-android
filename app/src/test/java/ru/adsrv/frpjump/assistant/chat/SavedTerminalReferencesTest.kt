package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolReference
import ru.adsrv.frpjump.agent.ToolResult
import ru.adsrv.frpjump.agent.TunnelReference

class SavedTerminalReferencesTest {
    @Test fun `saved tunnel identity round trips with history`() {
        val target = TunnelReference("p", "c", "host", 443, "WEB", true)
        val result = ToolResult("c", "profile_information", "row", ToolOutcome.SUCCESS,
            references = listOf(ToolReference("[tunnel:id]", "private evidence", "Work · host", target)))
        val encoded = Json.encodeToString(SavedResult.serializer(), SavedResult.of(result))
        assertFalse(encoded.contains("private evidence"))
        val restored = Json.decodeFromString(SavedResult.serializer(), encoded).value()
        assertEquals(target, restored.references.single().tunnel)
    }
    @Test fun `only opaque reference and label persist, legacy results infer none`() {
        val result = ToolResult("c", "terminal_tabs", "row", ToolOutcome.SUCCESS,
            references = listOf(ToolReference("[terminal:id]", "private evidence row", "1 · user@host")))
        val encoded = Json.encodeToString(SavedResult.serializer(), SavedResult.of(result))
        assertFalse(encoded.contains("private evidence row"))
        val restored = Json.decodeFromString(SavedResult.serializer(), encoded).value()
        assertEquals(listOf(ToolReference("[terminal:id]", label = "1 · user@host")), restored.references)
        val legacy = Json.decodeFromString(SavedResult.serializer(), """{"callId":"c","name":"terminal_tabs","text":"[terminal:id]","outcome":"SUCCESS"}""")
        assertEquals(emptyList<ToolReference>(), legacy.value().references)
    }
}
