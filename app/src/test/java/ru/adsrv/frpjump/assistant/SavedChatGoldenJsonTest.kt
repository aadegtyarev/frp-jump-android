package ru.adsrv.frpjump.assistant

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.assistant.chat.*

/** Pins the saved chat bytes: a refactoring must not change the encrypted history's plaintext. */
class SavedChatGoldenJsonTest {
    private val json = Json { encodeDefaults = true }
    private fun fixture(): SavedChat {
        val call = SavedCall("c1", "web_search", buildJsonObject { put("query", "board") })
        val reply = SavedReply("thinking", listOf(call), "opaque-continuation")
        val result = SavedResult("c1", "web_search", "found", "SUCCESS", 3)
        val states = SavedTurnState.entries.map { state ->
            SavedTurn("user-$state", listOf(SavedExchange(reply, listOf(result)), SavedExchange(SavedReply("final"))),
                listOf(SavedSource("Title", "https://example.org/")), state,
                if (state == SavedTurnState.FAILED) "TIMEOUT" else null, if (state == SavedTurnState.FAILED) 504 else null)
        }
        return SavedChat(1, ChatBinding("https://api.example/v1", "model-a"), states, "draft", true, 1, SavedSummary("sum", 1, 2))
    }
    @Test fun `saved chat json is byte identical`() {
        assertEquals(GOLDEN, json.encodeToString(fixture()))
    }
    @Test fun `golden json decodes to the fixture`() {
        assertEquals(fixture(), json.decodeFromString<SavedChat>(GOLDEN))
    }
    private companion object {
        const val GOLDEN = """{"version":1,"binding":{"root":"https://api.example/v1","model":"model-a"},"records":[{"user":"user-RUNNING","exchanges":[{"reply":{"text":"thinking","calls":[{"id":"c1","name":"web_search","arguments":{"query":"board"}}],"continuation":"opaque-continuation"},"results":[{"callId":"c1","name":"web_search","text":"found","outcome":"SUCCESS","omittedChars":3}]},{"reply":{"text":"final","calls":[],"continuation":null},"results":[]}],"sources":[{"title":"Title","url":"https://example.org/"}],"status":"RUNNING","problem":null,"problemStatus":null},{"user":"user-COMPLETE","exchanges":[{"reply":{"text":"thinking","calls":[{"id":"c1","name":"web_search","arguments":{"query":"board"}}],"continuation":"opaque-continuation"},"results":[{"callId":"c1","name":"web_search","text":"found","outcome":"SUCCESS","omittedChars":3}]},{"reply":{"text":"final","calls":[],"continuation":null},"results":[]}],"sources":[{"title":"Title","url":"https://example.org/"}],"status":"COMPLETE","problem":null,"problemStatus":null},{"user":"user-FAILED","exchanges":[{"reply":{"text":"thinking","calls":[{"id":"c1","name":"web_search","arguments":{"query":"board"}}],"continuation":"opaque-continuation"},"results":[{"callId":"c1","name":"web_search","text":"found","outcome":"SUCCESS","omittedChars":3}]},{"reply":{"text":"final","calls":[],"continuation":null},"results":[]}],"sources":[{"title":"Title","url":"https://example.org/"}],"status":"FAILED","problem":"TIMEOUT","problemStatus":504},{"user":"user-INTERRUPTED","exchanges":[{"reply":{"text":"thinking","calls":[{"id":"c1","name":"web_search","arguments":{"query":"board"}}],"continuation":"opaque-continuation"},"results":[{"callId":"c1","name":"web_search","text":"found","outcome":"SUCCESS","omittedChars":3}]},{"reply":{"text":"final","calls":[],"continuation":null},"results":[]}],"sources":[{"title":"Title","url":"https://example.org/"}],"status":"INTERRUPTED","problem":null,"problemStatus":null}],"draft":"draft","omitted":true,"contextStart":1,"summary":{"text":"sum","turn":1,"exchange":2}}"""
    }
}
