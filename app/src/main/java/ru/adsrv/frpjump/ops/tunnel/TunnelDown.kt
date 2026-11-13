package ru.adsrv.frpjump.ops.tunnel

import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.TunnelRefusal
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.undo.TunnelDisconnects

/** What to disconnect. */
sealed interface TunnelDownTarget {
    /** One tunnel (a row). */
    data class One(val tunnel: TunnelRef) : TunnelDownTarget
    /** Every tunnel of [profile] to [device]. */
    data class Device(val profile: String, val device: String) : TunnelDownTarget
    /** Every tunnel of [profile]. */
    data class Profile(val profile: String) : TunnelDownTarget
    /** Every tunnel of every profile. */
    data object All : TunnelDownTarget
}

/** What [TunnelDown] needs of the app besides the Undo queue. */
interface TunnelDownHost {
    /** The ids of the profiles that exist. */
    fun profiles(): Set<String>
    /** The tunnels of [profile] up now, as its manager lists them (whatever the screens show). */
    fun up(profile: String): List<TunnelRef>
    /** The profiles whose tunnels are all still being restored after a restart: nothing of them is on screen to wait for. */
    fun restoring(): Set<String>
    /** Tells [profile]'s server to drop its tunnels, in the app's scope and not waited for. */
    fun tellAsync(profile: String)
    /** Tells every server to drop its tunnels and returns once each was asked. */
    suspend fun tellAll()
}

/**
 * Disconnect, with Undo ([TunnelDisconnects]): the tunnels show down at once (before this suspends) and the server is told when the
 * notice goes, which is later and not part of this result. The effect is local now; the commit tells the server. With "all" and
 * nothing shown up, every server is told now and the result comes once they were asked; profiles still being restored are told at
 * once as well. A profile that does not exist is refused for [TunnelDownTarget.Device] and [TunnelDownTarget.Profile]. Used by the
 * screens only; the notification's "Disconnect all" does not use Undo and stays its own.
 */
class TunnelDown(
    private val disconnects: TunnelDisconnects,
    private val host: TunnelDownHost,
) : Operation<TunnelDownTarget, Unit> {
    override val name = "tunnel_down"
    override val effect = Effect.CHANGE_LOCAL

    override suspend fun invoke(input: TunnelDownTarget): OpResult<Unit> {
        when (input) {
            is TunnelDownTarget.One -> disconnects.disconnect(listOf(input.tunnel))
            is TunnelDownTarget.Profile -> {
                if (input.profile !in host.profiles()) return OpResult.Refused(TunnelRefusal.ProfileGone)
                disconnects.disconnect(disconnects.shown(host.up(input.profile)))
            }
            is TunnelDownTarget.Device -> {
                if (input.profile !in host.profiles()) return OpResult.Refused(TunnelRefusal.ProfileGone)
                disconnects.disconnect(disconnects.shownOn(host.up(input.profile), input.profile, input.device))
            }
            TunnelDownTarget.All -> {
                val profiles = host.profiles()
                val shown = disconnects.shown(profiles.flatMap(host::up))
                if (shown.isNotEmpty()) {
                    disconnects.disconnect(shown)
                    host.restoring().filter { it in profiles }.forEach(host::tellAsync)
                } else {
                    host.tellAll()
                }
            }
        }
        return OpResult.Done(Unit)
    }
}
