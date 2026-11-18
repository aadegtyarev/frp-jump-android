package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthPublickey

/**
 * Which key a key login got in with: the keys are offered one after another and the first the device accepts ends the login, so after a
 * successful login the last one offered is the one that was used. Lives for one login; it is known from the live connection only and is
 * stored nowhere (not in the login memory, not on disk).
 */
class KeyAttempts {
    @Volatile
    var offered: SshKeySlot? = null
        private set

    fun offer(slot: SshKeySlot) {
        offered = slot
    }
}

/** [AuthPublickey] of one of the app's keys that notes in [attempts] each time it is offered to the device. */
internal class RecordingPublickey(provider: KeyProvider, private val slot: SshKeySlot, private val attempts: KeyAttempts) : AuthPublickey(provider) {
    override fun request() {
        attempts.offer(slot)
        super.request()
    }
}

/** The name in [ru.adsrv.frpjump.profiles.PROFILE_COLORS] of the colour that marks [this] key: blue for the phone's, purple for the imported one. */
val SshKeySlot.colorName: String get() = if (this == SshKeySlot.IMPORTED) "purple" else "blue"
