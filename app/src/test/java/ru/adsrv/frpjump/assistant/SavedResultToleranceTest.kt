package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.assistant.chat.*

class SavedResultToleranceTest {
    private fun decode(outcome: String) = Json.decodeFromString(SavedResult.serializer(),
        """{"callId":"c","name":"ssh_command","text":"t","outcome":"$outcome","omittedChars":0}""")

    @Test fun `every known outcome reads back as itself`() {
        for (o in ToolOutcome.entries) assertEquals(o, decode(o.name).value().outcome)
    }

    @Test fun `an outcome from a newer version reads as a failure, neither success nor declined`() {
        for (name in listOf("CANCELLED", "declined", "", "SUCCESS ", "Success")) {
            val outcome = decode(name).value().outcome
            assertEquals(name, ToolOutcome.FAILED, outcome)
            assertNotEquals(ToolOutcome.SUCCESS, outcome); assertNotEquals(ToolOutcome.DECLINED, outcome)
        }
    }

    @Test fun `a history with an unknown outcome still restores its turn and counts the call as a failure`() {
        val turn = SavedTurn("q", listOf(SavedExchange(SavedReply("", listOf(SavedCall("c", "ssh_command", kotlinx.serialization.json.JsonObject(emptyMap())))), listOf(decode("FUTURE")))),
            status = SavedTurnState.COMPLETE)
        assertTrue(turn.view().toolFailed)
    }

    @Test fun `the pending question never prints or compares its command`() {
        val a = PendingConfirmation(1, AssistantToolId.SSH_COMMAND, "secret-command", null, null)
        val b = PendingConfirmation(1, AssistantToolId.SSH_COMMAND, "other", null, null)
        assertFalse(a.toString().contains("secret")); assertFalse(ChatState(confirmation = a).toString().contains("secret"))
        assertEquals(a, b); assertNotEquals(a, PendingConfirmation(2, AssistantToolId.SSH_COMMAND, "secret-command", null, null))
    }
}
