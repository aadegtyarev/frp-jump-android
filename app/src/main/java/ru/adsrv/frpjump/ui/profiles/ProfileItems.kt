package ru.adsrv.frpjump.ui.profiles

import ru.adsrv.frpjump.profiles.ProfileIndex

/** Where a profile is enrolled, as the owner knows it. */
data class ProfileServer(val host: String, val deviceName: String)

/** One profile as the switcher and the profile list show it. */
data class ProfileItem(
    val id: String,
    val name: String,
    /** A [ru.adsrv.frpjump.profiles.PROFILE_COLORS] name. */
    val color: String,
    /** Null while unknown or without an enrollment. */
    val server: ProfileServer?,
    val tunnels: Int,
    val current: Boolean,
    /** Its server rejected its token (401): the phone was deleted there. */
    val removed: Boolean,
) {
    /** The letter on its tile. */
    val letter: String get() = name.trim().take(1).uppercase().ifEmpty { "?" }
}

/** The profiles in the owner's order, with what each one has going on. */
fun profileItems(
    index: ProfileIndex,
    tunnels: Map<String, Int>,
    removed: Set<String>,
    servers: Map<String, ProfileServer?>,
): List<ProfileItem> = index.profiles.map {
    ProfileItem(
        id = it.id,
        name = it.name,
        color = it.color,
        server = servers[it.id],
        tunnels = tunnels[it.id] ?: 0,
        current = it.id == index.current,
        removed = it.id in removed,
    )
}

/**
 * Profiles deleted on their server that the device list does not show:
 * their polls stopped quietly, so the list says so in one line. (The
 * current one gets a screen of its own.)
 */
fun removedElsewhere(items: List<ProfileItem>): List<ProfileItem> = items.filter { it.removed && !it.current }
