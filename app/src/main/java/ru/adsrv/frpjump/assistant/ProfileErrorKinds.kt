package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.adsrv.frpjump.errors.DomainError
import ru.adsrv.frpjump.errors.ErrorKind

/**
 * The error fields of `profile_information` and the kinds each can hold: a model-visible format, listed in
 * `docs/agent-contract.md` ("Optional profile information"). A kind outside its field's set is told as [ErrorKind.UNEXPECTED], so the
 * listed sets stay true. The model gets the kind and nothing else: no server detail, host, message or class name.
 */
internal enum class ProfileErrorField(val path: String, val kinds: Set<ErrorKind>) {
    LOAD_ERROR("source_status.load_error", setOf(ErrorKind.PROFILES_LOAD_FAILED)),
    DEVICE_LIST_ERROR("results[].profile.device_list.error", SERVER_KINDS),
    SYNC_ERROR("results[].profile.tunnel_state.sync_error", SERVER_KINDS + ErrorKind.NO_FREE_PORT),
    MISMATCH("results[].profile.tunnel_state.mismatch", setOf(ErrorKind.PROTOCOL_UPDATE_APP, ErrorKind.PROTOCOL_UPDATE_SERVER)),
    CONNECTION_ERROR("results[].connection.error", SERVER_KINDS + ErrorKind.NOT_LISTED),
    OPERATION_ERROR("results[].operation.error", SERVER_KINDS + ErrorKind.NOT_LISTED);

    /** `{"kind": "<KIND>"}` for [error], null for none. */
    fun json(error: DomainError?): JsonElement = error?.let { kind(it.kind) } ?: JsonNull

    fun kind(kind: ErrorKind): JsonObject = buildJsonObject { put("kind", (kind.takeIf { it in kinds } ?: ErrorKind.UNEXPECTED).name) }
}

/** How a server or network call failed. */
private val SERVER_KINDS = setOf(
    ErrorKind.SERVER_ERROR,
    ErrorKind.NOT_HTTPS,
    ErrorKind.UNREACHABLE,
    ErrorKind.CONNECTION_BROKEN,
    ErrorKind.REDIRECTED,
    ErrorKind.UNEXPECTED_ANSWER,
    ErrorKind.UNEXPECTED,
)
