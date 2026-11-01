package ru.adsrv.frpjump.tunnel

/** What the tunnels notification counts: all tunnels up, and in how many profiles. */
data class TunnelSummary(
    val tunnels: Int,
    val profiles: Int,
    /** Tunnels still to come back after a restart; null when some are but how many is not read yet. */
    val restoring: Int? = 0,
    /** Tunnels still running while their disconnect waits for Undo: not in [tunnels]. */
    val dark: Int = 0,
) {
    /** "N tunnels in M profiles" is worth saying only when more than one profile has tunnels. */
    val namesProfiles: Boolean get() = profiles > 1

    companion object {
        fun of(byProfile: Map<String, Int>, restoring: Int?) = tunnelSummary(byProfile).copy(restoring = restoring)
    }
}

/** [byProfile]: tunnels up by profile id. */
fun tunnelSummary(byProfile: Map<String, Int>): TunnelSummary =
    TunnelSummary(byProfile.values.sum(), byProfile.count { it.value > 0 })

/** What the tunnels notification's title says. */
sealed interface TunnelsTitle {
    /** "N tunnels" (in M profiles). */
    data class Up(val summary: TunnelSummary) : TunnelsTitle

    /** "Restoring N tunnels…". */
    data class Restoring(val count: Int) : TunnelsTitle

    /** "Restoring tunnels…": just restarted, the record not read yet. */
    data object RestoringUnknown : TunnelsTitle

    /** "Disconnecting…": every tunnel left is waiting for its Undo to go; not "0 tunnels". */
    data object Disconnecting : TunnelsTitle
}

/** The tunnels up; with none, those going down, else restoring while none runs yet. */
fun tunnelsTitle(summary: TunnelSummary): TunnelsTitle = when {
    summary.tunnels > 0 -> TunnelsTitle.Up(summary)
    summary.dark > 0 -> TunnelsTitle.Disconnecting
    summary.restoring == null -> TunnelsTitle.RestoringUnknown
    summary.restoring > 0 -> TunnelsTitle.Restoring(summary.restoring)
    else -> TunnelsTitle.Up(summary)
}

/**
 * The notification's title: [tunnels] ("N tunnels…"), then
 * " · M sessions" while tunnels are up and [sessions] are open
 * ([sessionsText] words the count), since its "Disconnect all" cannot ask.
 */
fun notificationTitle(tunnels: String, title: TunnelsTitle, sessions: Int, sessionsText: (Int) -> String): String =
    if (sessions > 0 && title is TunnelsTitle.Up) "$tunnels · ${sessionsText(sessions)}" else tunnels
