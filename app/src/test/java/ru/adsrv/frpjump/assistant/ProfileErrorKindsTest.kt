package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.PROTOCOL_VERSION
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.devices.DeviceList
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.errors.DomainError
import ru.adsrv.frpjump.errors.ErrorKind
import ru.adsrv.frpjump.errors.ProtocolMismatch
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.Profile
import ru.adsrv.frpjump.profiles.ProfileInformationState
import ru.adsrv.frpjump.profiles.profileInformationPart
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState

/** The error kinds of `profile_information` are a model-visible format (`docs/agent-contract.md`): this pins them. */
class ProfileErrorKindsTest {
    private val server = listOf("SERVER_ERROR", "NOT_HTTPS", "UNREACHABLE", "CONNECTION_BROKEN", "REDIRECTED", "UNEXPECTED_ANSWER", "UNEXPECTED")

    /** The format as documented. Change it only together with `docs/agent-contract.md`. */
    private val documented = mapOf(
        ProfileErrorField.LOAD_ERROR to ("source_status.load_error" to listOf("PROFILES_LOAD_FAILED")),
        ProfileErrorField.DEVICE_LIST_ERROR to ("results[].profile.device_list.error" to server),
        ProfileErrorField.SYNC_ERROR to ("results[].profile.tunnel_state.sync_error" to server + "NO_FREE_PORT"),
        ProfileErrorField.MISMATCH to ("results[].profile.tunnel_state.mismatch" to listOf("PROTOCOL_UPDATE_APP", "PROTOCOL_UPDATE_SERVER")),
        ProfileErrorField.CONNECTION_ERROR to ("results[].connection.error" to server + "NOT_LISTED"),
        ProfileErrorField.OPERATION_ERROR to ("results[].operation.error" to server + "NOT_LISTED"),
    )

    @Test fun `the exact kinds of each error field are the documented ones`() {
        assertEquals(documented.keys.toList(), ProfileErrorField.entries)
        for ((field, expected) in documented) {
            assertEquals(field.name, expected.first, field.path)
            assertEquals(field.name, expected.second.toSet(), field.kinds.map { it.name }.toSet())
        }
    }

    @Test fun `the exact set of kind names is the documented one, and each is in some field`() {
        val all = listOf(
            "SERVER_ERROR", "NOT_HTTPS", "UNREACHABLE", "CONNECTION_BROKEN", "REDIRECTED", "UNEXPECTED_ANSWER", "UNEXPECTED",
            "NOT_LISTED", "NO_FREE_PORT", "PROTOCOL_UPDATE_APP", "PROTOCOL_UPDATE_SERVER", "PROFILES_LOAD_FAILED",
        )
        assertEquals(all, ErrorKind.entries.map { it.name })
        assertEquals(all.toSet(), ProfileErrorField.entries.flatMap { f -> f.kinds.map { it.name } }.toSet())
    }

    @Test fun `a field tells a kind it does not list as unexpected, and no error as null`() {
        assertEquals(buildJsonObject { put("kind", "UNEXPECTED") }, ProfileErrorField.DEVICE_LIST_ERROR.json(ProtocolMismatch(PROTOCOL_VERSION + 1)))
        assertEquals(buildJsonObject { put("kind", "UNEXPECTED") }, ProfileErrorField.CONNECTION_ERROR.json(DomainError.NoFreePort(1, 2)))
        assertEquals(buildJsonObject { put("kind", "NOT_LISTED") }, ProfileErrorField.CONNECTION_ERROR.json(DomainError.NotListed))
        assertEquals(buildJsonObject { put("kind", "NOT_LISTED") }, ProfileErrorField.OPERATION_ERROR.json(DomainError.NotListed))
        assertEquals(buildJsonObject { put("kind", "NO_FREE_PORT") }, ProfileErrorField.SYNC_ERROR.json(DomainError.NoFreePort(1, 2)))
        assertEquals(JsonNull, ProfileErrorField.SYNC_ERROR.json(null))
    }

    private val secrets = listOf("private-detail-text", "private-host.example", "private-exception-message", "private-redirect-target")

    private fun everyKind(): List<DomainError> = listOf(
        DomainError.ServerError(secrets[0]), DomainError.NotHttps(secrets[1]), DomainError.Unreachable(secrets[1], secrets[2]),
        DomainError.ConnectionBroken(secrets[1], secrets[2]), DomainError.Redirected(secrets[1], secrets[3]),
        DomainError.UnexpectedAnswer, DomainError.KeyMissing, DomainError.Other(secrets[2]), DomainError.NotListed, DomainError.NoFreePort(41111, 42222),
    )

    private fun information(error: DomainError, mismatch: ProtocolMismatch?, failure: String?): ProfileInformationState {
        val p = Profile("p", "Work", "blue", "alias")
        val tunnels = TunnelsState(
            listOf(Tunnel("same", 22, "grant", 40001, VisitorState(TunnelStatus.FAILED, detail = secrets[2], pinnedPortTaken = true))),
            pending = mapOf("same:22" to PendingOp.CONNECTING), errors = mapOf("same:22" to error, "orphan:80" to error),
            syncError = error, mismatch = mismatch,
        )
        val devices = DeviceList(listOf(DeviceRow("same", true, null, false)), stale = true, error = error, unreachable = true)
        val part = profileInformationPart(p, listOf(Connection("c", "same", 22, PortKind.SSH)), tunnels, emptySet(), devices)
        return ProfileInformationState(true, failure = failure, current = "p", profiles = listOf(part))
    }

    @Test fun `no server detail, host, message or class name reaches the model, whatever the error`() {
        val kinds = mutableSetOf<String>()
        for (error in everyKind()) for (mismatch in listOf(null, ProtocolMismatch(PROTOCOL_VERSION + 1), ProtocolMismatch(PROTOCOL_VERSION - 1))) {
            val text = profileInformationText(information(error, mismatch, failure = "PrivateLoadException"))
            for (secret in secrets + listOf("PrivateLoadException", "41111", "42222")) assertFalse("$error leaks $secret", text.contains(secret))
            // Every error field is an object with the kind alone.
            fun check(value: kotlinx.serialization.json.JsonElement?, field: ProfileErrorField) {
                if (value is JsonNull) return
                val o = value as JsonObject
                assertEquals(setOf("kind"), o.keys)
                val kind = o["kind"]!!.jsonPrimitive.content
                assertTrue("$field $kind", kind in field.kinds.map { it.name })
                kinds += kind
            }
            val page = Json.parseToJsonElement(text).jsonObject
            check(page["source_status"]!!.jsonObject["load_error"], ProfileErrorField.LOAD_ERROR)
            for (record in page["results"]!!.jsonArray.map { it.jsonObject }) {
                val profile = record["profile"]!!.jsonObject
                check(profile["device_list"]!!.jsonObject["error"], ProfileErrorField.DEVICE_LIST_ERROR)
                check(profile["tunnel_state"]!!.jsonObject["sync_error"], ProfileErrorField.SYNC_ERROR)
                check(profile["tunnel_state"]!!.jsonObject["mismatch"], ProfileErrorField.MISMATCH)
                profile["tunnel_state"]!!.jsonObject["mismatch"].let {
                    if (mismatch == null) assertEquals(JsonNull, it) else assertEquals(mismatch.kind.name, it!!.jsonObject["kind"]!!.jsonPrimitive.content)
                }
                (record["connection"] as? JsonObject)?.let { check(it["error"], ProfileErrorField.CONNECTION_ERROR) }
                (record["operation"] as? JsonObject)?.let { check(it["error"], ProfileErrorField.OPERATION_ERROR) }
            }
        }
        // Every kind the app can produce for these fields shows up, so the loop above covered each branch.
        assertTrue(kinds.containsAll(server))
        assertTrue(kinds.containsAll(listOf("NOT_LISTED", "NO_FREE_PORT", "PROTOCOL_UPDATE_APP", "PROTOCOL_UPDATE_SERVER", "PROFILES_LOAD_FAILED")))
    }

    @Test fun `the golden record has the kinds in the five error fields and nothing else changed`() {
        val state = information(DomainError.Unreachable(secrets[1], secrets[2]), ProtocolMismatch(PROTOCOL_VERSION + 1), failure = "x")
        val page = Json.parseToJsonElement(profileInformationText(state)).jsonObject
        assertEquals("""{"kind":"PROFILES_LOAD_FAILED"}""", page["source_status"]!!.jsonObject["load_error"].toString())
        val record = page["results"]!!.jsonArray.map { it.jsonObject }.first { it["connection"] is JsonObject }
        val profile = record["profile"]!!.jsonObject
        assertEquals(
            """{"known":true,"updated_at":null,"stale":true,"unreachable":true,"error":{"kind":"UNREACHABLE"}}""",
            profile["device_list"].toString(),
        )
        assertEquals(
            """{"sync_error":{"kind":"UNREACHABLE"},"mismatch":{"kind":"PROTOCOL_UPDATE_APP"},"waiting":null,"pending_count":1,"error_count":2}""",
            profile["tunnel_state"].toString(),
        )
        assertEquals("""{"kind":"UNREACHABLE"}""", record["connection"]!!.jsonObject["error"].toString())
        val operation = page["results"]!!.jsonArray.map { it.jsonObject }.first { it["operation"] is JsonObject }["operation"]!!.jsonObject
        assertEquals("""{"kind":"UNREACHABLE"}""", operation["error"].toString())
    }

    @Test fun `a field that does not list unexpected refuses a kind it does not list`() {
        for (field in listOf(ProfileErrorField.LOAD_ERROR, ProfileErrorField.MISMATCH)) {
            assertFalse(ErrorKind.UNEXPECTED in field.kinds)
            assertThrows(IllegalArgumentException::class.java) { field.kind(ErrorKind.SERVER_ERROR) }
            assertThrows(IllegalArgumentException::class.java) { field.json(DomainError.NotListed) }
        }
        for (field in ProfileErrorField.entries - listOf(ProfileErrorField.LOAD_ERROR, ProfileErrorField.MISMATCH))
            assertTrue(ErrorKind.UNEXPECTED in field.kinds)
    }

    @Test fun `no error is null in every field`() {
        val p = Profile("p", "Work", "blue", "alias")
        val devices = DeviceList(listOf(DeviceRow("same", true, null, false)))
        val part = profileInformationPart(p, listOf(Connection("c", "same", 22, PortKind.SSH)), TunnelsState(), emptySet(), devices)
        val page = Json.parseToJsonElement(profileInformationText(ProfileInformationState(true, current = "p", profiles = listOf(part)))).jsonObject
        assertEquals(JsonNull, page["source_status"]!!.jsonObject["load_error"])
        val record = page["results"]!!.jsonArray.map { it.jsonObject }.first { it["connection"] is JsonObject }
        val profile = record["profile"]!!.jsonObject
        assertEquals(JsonNull, profile["device_list"]!!.jsonObject["error"])
        assertEquals(JsonNull, profile["tunnel_state"]!!.jsonObject["sync_error"])
        assertEquals(JsonNull, profile["tunnel_state"]!!.jsonObject["mismatch"])
        assertEquals(JsonNull, record["connection"]!!.jsonObject["error"])
    }
}
