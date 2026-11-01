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
    /** Storage: called on the IO dispatcher ([DirectNotifier.retried]). */
    private val retried: (profile: String, device: String) -> Unit = { _, _ -> },
) {
    /**
     * "Keep through the server": no more attempts or offers for [device] of
     * [profile] on the current network; its tunnels switch to the server
     * alone (connections already going through it stay). False when the
     * phone is on no network it can tie the choice to: nothing changed.
     */
    suspend fun keepThroughServer(profile: String, device: String): Boolean {
        if (!withContext(Dispatchers.IO) { kept.keep(profile, device, network.value) }) return false
        profiles.runtime(profile)?.tunnels?.reapply()
        return true
    }

    /** "Retry direct": every tunnel of [device] in [profile] that is not direct tries again, here and now. */
    suspend fun retryDirect(profile: String, device: String) {
        withContext(Dispatchers.IO) {
            kept.release(profile, device, network.value)
            retried(profile, device)
        }
        profiles.runtime(profile)?.tunnels?.retryDirect(device)
    }
}
