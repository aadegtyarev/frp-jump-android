package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.flow.first
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.profiles.ProfileInformation
import ru.adsrv.frpjump.profiles.ProfileInformationState

/** Explicit invocation over the shared source; no inventory owner, cache or connection control. */
internal class AssistantProfileInformationTool(
    private val refresh: suspend () -> Unit,
    private val read: suspend () -> ProfileInformationState,
    dispatch: WebDispatch,
) : AppTool<ProfileInformationQuery>(AssistantToolId.PROFILE_INFORMATION, DESCRIPTION, SCHEMA, dispatch) {
    constructor(information: ProfileInformation, dispatch: WebDispatch) : this(
        { information.refreshAll { operation, start -> dispatch("profile-$operation-dispatch", start) } },
        { information.observe().first() }, dispatch,
    )

    override fun parse(args: StrictArgs) = args.run {
        onlyKeys("query", "profile_id", "offset")
        ProfileInformationQuery(optionalString("query", 256, allowBlank = true).orEmpty().trim(),
            optionalString("profile_id", 256), optionalInt("offset", min = 0) ?: 0)
    }
    override val invalidArguments = ToolOutput("Invalid profile information arguments: optional query/profile_id strings up to 256 characters and a nonnegative integer offset.", ToolOutcome.FAILED)
    override suspend fun run(args: ProfileInformationQuery): ToolOutput {
        refresh()
        return ToolOutput(profileInformationText(read(), args))
    }
    override fun failure(error: Exception) = ToolOutput("Could not obtain profile information. No successful refresh is implied.", ToolOutcome.FAILED)

    private companion object {
        const val DESCRIPTION = "Find app-known devices and saved connections across all profiles, refreshing device lists once per invocation. " +
            "Optional query matches part of a device name/label, connection name or profile name (case/NFC insensitive); profile_id selects an exact profile. " +
            "Empty arguments browse. Results are whole records: follow next_offset with the same query/profile_id when needed. " +
            "Give a concise answer with all suitable variants, distinguished by profile/device/connection, not the whole catalogue or only the first match. " +
            "Several matches are not a failure; ask for a choice only if needed for a subsequent action. Use exact profile.id and connection.id for saved targets. " +
            "Disclose incomplete/stale sources; zero known matches is not absence from unknown sources. App state is not service readiness. " +
            "Private information goes to the configured model."
        val SCHEMA = objectSchema { string("query", 256, required = false); string("profile_id", 256, required = false); integer("offset", 0, required = false) }
    }
}
