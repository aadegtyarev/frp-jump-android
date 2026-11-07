package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.profiles.ProfileInformation
import ru.adsrv.frpjump.profiles.ProfileInformationState

/** Explicit field projection: never serialize runtime/store objects or diagnostic strings. */
internal class AssistantProfileInformationTool(
    private val refresh: suspend () -> Unit,
    private val read: suspend () -> ProfileInformationState,
    private val dispatch: WebDispatch,
) : AgentTool {
    constructor(information: ProfileInformation, dispatch: WebDispatch) : this(
        { information.refreshAll { operation, start -> dispatch("profile-$operation-dispatch", start) } },
        { information.observe().first() }, dispatch,
    )

    override val spec = ToolSpec("profile_information",
        "Refresh device lists once and read app-known devices, saved connections and tunnels across all profiles. May be unavailable or stale; not proof of remote service readiness. Private information is sent to the configured model.",
        buildJsonObject {
            put("type", "object"); putJsonObject("properties") { }; put("additionalProperties", false)
        })

    override suspend fun execute(arguments: JsonObject): ToolOutput {
        dispatch("tool-start") { }
        if (arguments.isNotEmpty()) return ToolOutput("Profile information takes no arguments.", ToolOutcome.FAILED)
        return try {
            refresh()
            ToolOutput(profileInformationText(read()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ToolOutput("Could not obtain profile information. No successful refresh is implied.", ToolOutcome.FAILED) }
    }
}

internal fun profileInformationText(state: ProfileInformationState): String = buildJsonObject {
    put("notice", "App-known information, not an atomic live inventory or proof of service readiness. Error text is withheld; error fields report only what the app knows. Missing/null information is unknown, not empty success.")
    put("loaded", state.loaded)
    put("load_error", if (state.failure != null) JsonPrimitive("Profile loading failed") else JsonNull)
    put("retrying", state.retrying)
    putJsonArray("profiles") { state.profiles.forEach { p -> add(buildJsonObject {
        put("id", p.id); put("name", p.name); put("color", p.color)
        put("activation_pending", p.activationPending); put("available", p.available)
        put("device_list", p.devices?.let { list -> buildJsonObject {
            put("updated_at", list.updatedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("stale", list.stale); put("unreachable", list.unreachable)
            put("error", if (list.error != null) JsonPrimitive("Device list refresh failed") else JsonNull)
            put("devices", list.devices?.let { devices -> buildJsonArray { devices.forEach { d -> add(buildJsonObject {
                put("name", d.name); put("label", d.label?.let(::JsonPrimitive) ?: JsonNull)
                put("online", d.online); put("disabled", d.disabled); put("stale", d.stale); put("checking", d.checking)
                put("last_seen", d.lastSeen?.let { age -> buildJsonObject { put("unit", age.unit.name); put("count", age.count) } } ?: JsonNull)
            }) } } } ?: JsonNull)
        } } ?: JsonNull)
        put("connections", if (!p.available) JsonNull else buildJsonArray { p.rows.forEach { row -> add(buildJsonObject {
            val c = row.connection
            put("id", c.id); put("device", c.device); put("port", c.port); put("kind", c.kind.name)
            put("name", c.customName?.let(::JsonPrimitive) ?: JsonNull); put("saved", row.saved)
            put("https", c.https); put("user", c.user?.let(::JsonPrimitive) ?: JsonNull)
            put("pinned_local_port", c.localPort?.let(::JsonPrimitive) ?: JsonNull)
            put("pending", row.pending?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("error", if (row.error != null) JsonPrimitive("Connection operation failed") else JsonNull)
            put("tunnel", row.tunnel?.let { t -> buildJsonObject {
                put("local_address", "127.0.0.1:${t.localPort}"); put("status", t.state.status.name)
                put("direct_mode", t.direct.name); put("pinned", t.pinned)
                put("direct_failed", t.state.directFailed); put("waiting_for_peer", t.state.waitingForPeer)
                put("confirming", t.state.confirming); put("waiting_for_device", t.state.waitingForDevice)
                put("device_missing", t.state.deviceMissing)
            } } ?: JsonNull)
        }) } })
        put("tunnel_state", p.tunnels?.let { t -> buildJsonObject {
            put("sync_error", if (t.syncError != null) JsonPrimitive("Tunnel synchronization failed") else JsonNull)
            put("mismatch", if (t.mismatch != null) JsonPrimitive("Protocol mismatch") else JsonNull)
            put("waiting", t.waiting?.name?.let(::JsonPrimitive) ?: JsonNull)
            putJsonObject("pending") { t.pending.forEach { (key, operation) -> put(key, operation.name) } }
            putJsonObject("errors") { t.errors.keys.forEach { key -> put(key, "Connection operation failed") } }
        } } ?: JsonNull)
    }) } }
}.toString()
