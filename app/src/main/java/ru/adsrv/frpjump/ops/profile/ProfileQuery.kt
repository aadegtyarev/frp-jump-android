package ru.adsrv.frpjump.ops.profile

import kotlinx.coroutines.CancellationException
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.ProfileQueryFailure
import ru.adsrv.frpjump.ops.RemoteOutcome
import ru.adsrv.frpjump.profiles.ProfileInformationState

/** Which part of the app-known catalogue is wanted. The operation reads it all; an adapter uses the query to select and page what it shows. */
data class ProfileInformationQuery(val query: String = "", val profileId: String? = null, val offset: Int = 0)

/**
 * The app-known devices and saved connections of every profile, after refreshing the device lists once. Reads only: no inventory owner,
 * cache or connection control. [refresh] and [read] are the shared source's; the caller chooses how the refresh is admitted.
 */
class ProfileQuery(
    private val refresh: suspend () -> Unit,
    private val read: suspend () -> ProfileInformationState,
) : Operation<ProfileInformationQuery, ProfileInformationState> {
    override val name = "profile_query"
    override val effect = Effect.READ_REMOTE

    override suspend fun invoke(input: ProfileInformationQuery): OpResult<ProfileInformationState> = try {
        refresh()
        OpResult.Done(read())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        // A refresh may have reached some servers: it is a read, so nothing changed, but its outcome is not known.
        OpResult.Failed(ProfileQueryFailure.Unavailable, RemoteOutcome.UNKNOWN)
    }
}
