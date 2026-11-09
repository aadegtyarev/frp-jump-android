package ru.adsrv.frpjump.assistant

import kotlinx.serialization.json.*
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.devices.searchMatches
import ru.adsrv.frpjump.profiles.ProfileInformationPart
import ru.adsrv.frpjump.profiles.ProfileInformationState

internal data class ProfileInformationQuery(val query: String = "", val profileId: String? = null, val offset: Int = 0) {
    companion object {
        fun parse(args: JsonObject): ProfileInformationQuery {
            require(args.keys.all { it in setOf("query", "profile_id", "offset") })
            fun text(key: String): String? {
                if (key !in args) return null
                val value = args[key] as? JsonPrimitive
                require(value != null && value.isString && value.content.length <= 256)
                return value.content
            }
            val profile = text("profile_id")
            require(profile == null || profile.isNotBlank())
            val offset = if ("offset" !in args) 0 else {
                val value = args["offset"] as? JsonPrimitive
                require(value != null && !value.isString)
                requireNotNull(value.intOrNull).also { require(it >= 0) }
            }
            return ProfileInformationQuery(text("query").orEmpty().trim(), profile, offset)
        }
    }
}

/** A bounded live selection, not a snapshot/cache. Records are rendered only for this page. */
internal fun profileInformationText(state: ProfileInformationState, query: ProfileInformationQuery = ProfileInformationQuery()): String {
    val profiles = state.profiles.filter { query.profileId == null || it.id == query.profileId }
    val coverage = buildJsonObject {
        put("loaded", state.loaded)
        put("load_error", if (state.failure != null) JsonPrimitive("Profile loading failed") else JsonNull)
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
    var total = 0
    var full = false
    for (render in profileRecords(profiles, query.query)) {
        val position = total++
        if (position < query.offset || full) continue
        var record = render(256)
        fun fits() = page(selected + record, Int.MAX_VALUE, Int.MAX_VALUE).length <= 8000
        if (!fits()) {
            if (selected.isNotEmpty()) { full = true; continue }
            // Only pathological display fields are shortened; saved action identifiers stay exact.
            for (limit in listOf(128, 64, 32, 0)) {
                record = render(limit)
                if (fits()) break
            }
            if (!fits()) record = buildJsonObject {
                put("record_type", "unavailable"); put("record_offset", position)
                put("error", "Record identifiers exceed the output budget; no action identifiers are supplied.")
            }
        }
        selected += record
    }
    val next = (query.offset.toLong() + selected.size).takeIf { selected.isNotEmpty() && it < total }?.toInt()
    return page(selected, total, next)
}

/** Matching precedes paging and examines the complete available source, never a clipped string. */
private fun profileRecords(profiles: List<ProfileInformationPart>, query: String): Sequence<(Int) -> JsonObject> = sequence {
    for (p in profiles) {
        val all = query.isEmpty() || searchMatches(p.name, query) || p.id == query
        if (all) yield { limit -> profileRecord(p, null, null, limit) }
        if (!p.available) continue
        for (device in p.devices?.devices.orEmpty()) {
            if (all || searchMatches(device.name, query) || searchMatches(device.label, query))
                yield { limit -> profileRecord(p, device, null, limit) }
        }
        for (row in p.rows) {
            val device = p.devices?.devices?.firstOrNull { it.name == row.connection.device }
            if (all || searchMatches(row.connection.device, query) || searchMatches(device?.label, query) ||
                searchMatches(row.connection.customName, query) || row.connection.id == query)
                yield { limit -> profileRecord(p, device, row, limit) }
        }
        val tunnels = p.tunnels
        for (key in tunnels?.let { it.pending.keys + it.errors.keys }.orEmpty()) {
            if (all || searchMatches(key, query)) yield { limit -> profileRecord(p, null, null, limit, key) }
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
                put("error", if (d.error != null) JsonPrimitive("Device list refresh failed") else JsonNull)
            } } ?: JsonNull)
            put("tunnel_state", p.tunnels?.let { t -> buildJsonObject {
                put("sync_error", if (t.syncError != null) JsonPrimitive("Tunnel synchronization failed") else JsonNull)
                put("mismatch", if (t.mismatch != null) JsonPrimitive("Protocol mismatch") else JsonNull)
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
            put("error", if (r.error != null) JsonPrimitive("Connection operation failed") else JsonNull)
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
            put("error", if (p.tunnels?.errors?.containsKey(operationKey) == true) JsonPrimitive("Connection operation failed") else JsonNull)
        }
        put("shortened_fields", JsonArray(shortened.map(::JsonPrimitive)))
    }
}
