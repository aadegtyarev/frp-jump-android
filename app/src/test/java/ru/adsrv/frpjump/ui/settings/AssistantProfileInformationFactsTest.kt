package ru.adsrv.frpjump.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat's facts view re-reads `profile_information` results, also those saved by 0.3.70 and earlier, whose error fields are a
 * fixed phrase (a string); newer ones hold `{"kind": ...}`. Both show the operation-error line.
 */
class AssistantProfileInformationFactsTest {
    private fun operation(error: String) = Json.parseToJsonElement(
        """{"results":[{"record_type":"tunnel_operation","operation":{"key":"wb:22","pending":null,"error":$error}}]}""",
    ).jsonObject["results"]!!.jsonArray.single().jsonObject["operation"]!!.jsonObject

    @Test fun `an old saved phrase shows the operation error line`() =
        assertTrue(operationFailed(operation("\"Connection operation failed\"")))

    @Test fun `a kind object shows the operation error line`() {
        assertTrue(operationFailed(operation("""{"kind":"UNREACHABLE"}""")))
        assertTrue(operationFailed(operation("""{"kind":"NOT_LISTED"}""")))
    }

    @Test fun `no error shows no line`() {
        assertFalse(operationFailed(operation("null")))
        assertFalse(operationFailed(Json.parseToJsonElement("""{"key":"wb:22"}""").jsonObject))
    }
}
