package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.profiles.ProfileInformation
import ru.adsrv.frpjump.profiles.ProfileInformationState

/** Explicit invocation over the shared source; no inventory owner, cache or connection control. */
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
        "Find app-known devices and saved connections across all profiles, refreshing device lists once per invocation. " +
            "Optional query matches part of a device name/label, connection name or profile name (case/NFC insensitive); profile_id selects an exact profile. " +
            "Empty arguments browse. Results are whole records: follow next_offset with the same query/profile_id when needed. " +
            "Give a concise answer with all suitable variants, distinguished by profile/device/connection, not the whole catalogue or only the first match. " +
            "Several matches are not a failure; ask for a choice only if needed for a subsequent action. Use exact profile.id and connection.id for saved targets. " +
            "Disclose incomplete/stale sources; zero known matches is not absence from unknown sources. App state is not service readiness. " +
            "Private information goes to the configured model.",
        buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string"); put("maxLength", 256) }
                putJsonObject("profile_id") { put("type", "string"); put("maxLength", 256) }
                putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
            }
        })

    override suspend fun execute(arguments: JsonObject): ToolOutput {
        dispatch("tool-start") { }
        val query = try { ProfileInformationQuery.parse(arguments) } catch (_: IllegalArgumentException) {
            return ToolOutput("Invalid profile information arguments: optional query/profile_id strings up to 256 characters and a nonnegative integer offset.", ToolOutcome.FAILED)
        }
        return try {
            refresh()
            ToolOutput(profileInformationText(read(), query))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ToolOutput("Could not obtain profile information. No successful refresh is implied.", ToolOutcome.FAILED) }
    }
}
