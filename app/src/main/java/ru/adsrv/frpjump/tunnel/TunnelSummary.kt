package ru.adsrv.frpjump.tunnel

/** What the tunnels notification counts: all tunnels up, and in how many profiles. */
data class TunnelSummary(val tunnels: Int, val profiles: Int) {
    /** "N tunnels in M profiles" is worth saying only when more than one profile has tunnels. */
    val namesProfiles: Boolean get() = profiles > 1
}

/** [byProfile]: tunnels up by profile id. */
fun tunnelSummary(byProfile: Map<String, Int>): TunnelSummary =
    TunnelSummary(byProfile.values.sum(), byProfile.count { it.value > 0 })
