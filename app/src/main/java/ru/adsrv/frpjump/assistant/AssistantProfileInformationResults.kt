package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.agent.ToolReference
import ru.adsrv.frpjump.agent.TunnelReference
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.errors.ErrorKind
import ru.adsrv.frpjump.devices.searchMatches
import ru.adsrv.frpjump.ops.profile.ProfileInformationQuery
import ru.adsrv.frpjump.ops.ssh.sshResultBudget
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.profiles.ProfileInformationPart
import ru.adsrv.frpjump.profiles.ProfileInformationState

/**
 * A bounded live selection, not a snapshot/cache. Records are rendered only for this page. Whole-record pages of at most [budget] characters: the owner's tool-output
 * limit less the same overhead the other output budgets keep ([sshResultBudget]), so the kernel never cuts a page in the middle of a record.
 */
internal fun profileInformationText(state: ProfileInformationState, query: ProfileInformationQuery = ProfileInformationQuery(),
    budget: Int = sshResultBudget(AssistantLimits.DEFAULT_TOOL_OUTPUT_CHARS)): String =
    profileInformationPage(state, query, budget, issueReferences = false).text

internal fun profileInformationOutput(state: ProfileInformationState, query: ProfileInformationQuery, budget: Int): ToolOutput =
    profileInformationPage(state, query, budget, issueReferences = true)

private fun profileInformationPage(state: ProfileInformationState, query: ProfileInformationQuery,
    budget: Int, issueReferences: Boolean): ToolOutput {
    val profiles = state.profiles.filter { query.profileId == null || it.id == query.profileId }
    val coverage = buildJsonObject {
        put("loaded", state.loaded)
        put("load_error", if (state.failure != null) ProfileErrorField.LOAD_ERROR.kind(ErrorKind.PROFILES_LOAD_FAILED) else JsonNull)
        put("retrying", state.retrying)
        put("profiles", profiles.size)
        put("unavailable_profiles", profiles.count { !it.available })
        put("unknown_device_lists", profiles.count { it.devices?.devices == null })
        put("stale_device_lists", profiles.count { it.devices?.let { d -> d.stale || d.devices?.any { row -> row.stale || row.checking } == true } == true })
        put("unreachable_profiles", profiles.count { it.devices?.unreachable == true })
        put("complete", state.loaded && state.failure == null && profiles.all { p ->
            p.available && p.devices?.let { d -> d.devices != null && !d.stale && !d.unreachable && d.error == null && d.devices.none { it.stale || it.checking } } == true
        })
    }
    fun page(records: List<JsonObject>, total: Int, next: Int?) = buildJsonObject {
        put("notice", "App-known data, not service readiness. Missing data is unknown. Live selection: if the catalogue changes between pages, restart at offset 0; offsets are not a snapshot.")
        put("query", query.query); put("profile_id", query.profileId?.let(::JsonPrimitive) ?: JsonNull)
        put("source_status", coverage)
        put("offset", query.offset); put("total_records", total)
        put("next_offset", next?.let(::JsonPrimitive) ?: JsonNull)
        put("results", JsonArray(records))
    }.toString()
    val selected = ArrayList<JsonObject>()
    val references = ArrayList<ToolReference>()
    var total = 0
    var full = false
    for (recordSource in profileRecords(profiles, query.query)) {
        val render = recordSource.render
        val row = recordSource.row
        val position = total++
        if (position < query.offset || full) continue
        val token = if (issueReferences && row?.saved == true) "[tunnel:${java.util.UUID.randomUUID()}]" else null
        fun withToken(record: JsonObject) = if (token == null) record else JsonObject(record + ("reference" to JsonPrimitive(token)))
        var record = withToken(render(256))
        fun fits() = page(selected + record, Int.MAX_VALUE, Int.MAX_VALUE).length <= budget
        if (!fits()) {
            if (selected.isNotEmpty()) { full = true; continue }
            // Only pathological display fields are shortened; saved action identifiers stay exact.
            for (limit in listOf(128, 64, 32, 0)) {
                record = withToken(render(limit))
                if (fits()) break
            }
            if (!fits()) record = buildJsonObject {
                put("record_type", "unavailable"); put("record_offset", position)
                put("error", "Record identifiers exceed the output budget; no action identifiers are supplied.")
            }
        }
        selected += record
        if (token != null && row != null && record["reference"] != null) {
            val c = row.connection
            references += ToolReference(token, label = listOf(record.getValue("profile").jsonObject.getValue("name").jsonPrimitive.content,
                record.getValue("connection").jsonObject.getValue("device").jsonPrimitive.content,
                record.getValue("connection").jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "${c.kind} ${c.port}").joinToString(" · "),
                tunnel = TunnelReference(row.profile, c.id, c.device, c.port, c.kind.name, c.https))
        }
    }
    val next = (query.offset.toLong() + selected.size).takeIf { selected.isNotEmpty() && it < total }?.toInt()
    val text = page(selected, total, next)
    return ToolOutput(text, references = references.map { it.copy(evidence = text) })
}

/** Matching precedes paging and examines the complete available source, never a clipped string. */
private data class ProfileRecord(val row: ConnectionRow? = null, val render: (Int) -> JsonObject)
private fun profileRecords(profiles: List<ProfileInformationPart>, query: String): Sequence<ProfileRecord> = sequence {
    for (p in profiles) {
        val all = query.isEmpty() || searchMatches(p.name, query) || p.id == query
        if (all) yield(ProfileRecord { limit -> profileRecord(p, null, null, limit) })
        if (!p.available) continue
        for (device in p.devices?.devices.orEmpty()) {
            if (all || searchMatches(device.name, query) || searchMatches(device.label, query))
                yield(ProfileRecord { limit -> profileRecord(p, device, null, limit) })
        }
        for (row in p.rows) {
            val device = p.devices?.devices?.firstOrNull { it.name == row.connection.device }
            if (all || searchMatches(row.connection.device, query) || searchMatches(device?.label, query) ||
                searchMatches(row.connection.customName, query) || row.connection.id == query)
                yield(ProfileRecord(row) { limit -> profileRecord(p, device, row, limit) })
        }
        val tunnels = p.tunnels
        for (key in tunnels?.let { it.pending.keys + it.errors.keys }.orEmpty()) {
            if (all || searchMatches(key, query)) yield(ProfileRecord { limit -> profileRecord(p, null, null, limit, key) })
        }
    }
}

/** Safe typed fields only. Never serialize owners, credentials or arbitrary diagnostic values. */
private fun profileRecord(p: ProfileInformationPart, device: DeviceRow?, row: ConnectionRow?, textLimit: Int, operationKey: String? = null): JsonObject {
    val shortened = ArrayList<String>()
    fun text(value: String, field: String): String {
        if (value.length <= textLimit) return value
        shortened += field
        val end = if (textLimit > 0 && value[textLimit - 1].isHighSurrogate()) textLimit - 1 else textLimit
        return value.substring(0, end)
    }
    return buildJsonObject {
        put("record_type", when { operationKey != null -> "tunnel_operation"; row != null -> "connection"; device != null -> "device"; else -> "profile" })
        putJsonObject("profile") {
            put("id", p.id); put("name", text(p.name, "profile.name")); put("color", text(p.color, "profile.color"))
            put("activation_pending", p.activationPending); put("available", p.available)
            put("device_list", p.devices?.let { d -> buildJsonObject {
                put("known", d.devices != null); put("updated_at", d.updatedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("stale", d.stale); put("unreachable", d.unreachable)
                put("error", ProfileErrorField.DEVICE_LIST_ERROR.json(d.error))
            } } ?: JsonNull)
            put("tunnel_state", p.tunnels?.let { t -> buildJsonObject {
                put("sync_error", ProfileErrorField.SYNC_ERROR.json(t.syncError))
                put("mismatch", ProfileErrorField.MISMATCH.json(t.mismatch))
                put("waiting", t.waiting?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("pending_count", t.pending.size); put("error_count", t.errors.size)
            } } ?: JsonNull)
        }
        put("device", device?.let { d -> buildJsonObject {
            put("name", text(d.name, "device.name")); put("label", d.label?.let { JsonPrimitive(text(it, "device.label")) } ?: JsonNull)
            put("online", d.online); put("disabled", d.disabled); put("stale", d.stale); put("checking", d.checking)
            put("last_seen", d.lastSeen?.let { age -> buildJsonObject { put("unit", age.unit.name); put("count", age.count) } } ?: JsonNull)
        } } ?: JsonNull)
        put("connection", row?.let { r -> buildJsonObject {
            val c = r.connection
            put("id", c.id); put("device", text(c.device, "connection.device")); put("port", c.port); put("kind", c.kind.name)
            put("name", c.customName?.let { JsonPrimitive(text(it, "connection.name")) } ?: JsonNull); put("saved", r.saved)
            put("https", c.https); put("user", c.user?.let { JsonPrimitive(text(it, "connection.user")) } ?: JsonNull)
            put("pinned_local_port", c.localPort?.let(::JsonPrimitive) ?: JsonNull)
            put("pending", r.pending?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("error", ProfileErrorField.CONNECTION_ERROR.json(r.error))
            put("tunnel", r.tunnel?.let { t -> buildJsonObject {
                put("local_address", "127.0.0.1:${t.localPort}"); put("status", t.state.status.name)
                put("direct_mode", t.direct.name); put("pinned", t.pinned)
                put("direct_failed", t.state.directFailed); put("waiting_for_peer", t.state.waitingForPeer)
                put("confirming", t.state.confirming); put("waiting_for_device", t.state.waitingForDevice)
                put("device_missing", t.state.deviceMissing)
            } } ?: JsonNull)
        } } ?: JsonNull)
        if (operationKey != null) putJsonObject("operation") {
            put("key", text(operationKey, "operation.key"))
            put("pending", p.tunnels?.pending?.get(operationKey)?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("error", ProfileErrorField.OPERATION_ERROR.json(p.tunnels?.errors?.get(operationKey)))
        }
        put("shortened_fields", JsonArray(shortened.map(::JsonPrimitive)))
    }
}
