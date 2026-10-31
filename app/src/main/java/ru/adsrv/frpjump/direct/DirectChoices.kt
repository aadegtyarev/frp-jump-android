package ru.adsrv.frpjump.direct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.profiles.Profiles

/**
 * The owner's two answers to "Direct didn't work", from the notification
 * or the card: the same code either way.
 */
class DirectChoices(
    private val profiles: Profiles,
    private val kept: DirectKeptStore,
    private val network: StateFlow<String?>,
) {
    /**
     * "Keep through the server": no more attempts or offers for [device] of
     * [profile] on the current network; its tunnels switch to the server
     * alone (connections already going through it stay).
     */
    suspend fun keepThroughServer(profile: String, device: String) {
        withContext(Dispatchers.IO) { kept.keep(profile, device, network.value) }
        profiles.runtime(profile)?.tunnels?.reapply()
    }

    /** "Retry direct": [device] of [profile] tries direct again here, [port]'s tunnel at once. */
    suspend fun retryDirect(profile: String, device: String, port: Int) {
        withContext(Dispatchers.IO) { kept.release(profile, device, network.value) }
        profiles.runtime(profile)?.tunnels?.retryDirect(device, port)
    }
}
