package ru.adsrv.frpjump.profiles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * One enrollment: a server, a device name and its own credentials, sealed
 * with the Keystore key [keyAlias]. Its files live in `profiles/<id>/`.
 */
@Serializable
data class Profile(
    /** A random UUID; never shown, never reused. */
    val id: String,
    val name: String,
    /** A name from [PROFILE_COLORS]; each theme maps it to its own shade. */
    val color: String,
    @SerialName("key_alias") val keyAlias: String,
)

/** `profiles.json`: the profiles in the owner's order and which one the app shows. */
@Serializable
data class ProfileIndex(
    val profiles: List<Profile> = emptyList(),
    val current: String? = null,
) {
    fun find(id: String): Profile? = profiles.firstOrNull { it.id == id }

    val currentProfile: Profile? get() = current?.let(::find)

    /** [profile] appended (or replaced in place when its id is known), and shown when [makeCurrent]. */
    fun plus(profile: Profile, makeCurrent: Boolean = true): ProfileIndex {
        val list = if (find(profile.id) != null) profiles.map { if (it.id == profile.id) profile else it } else profiles + profile
        return copy(profiles = list, current = if (makeCurrent || current == null) profile.id else current)
    }

    /** Without [id]; if it was shown, the next profile is (the previous one when it was last). */
    fun minus(id: String): ProfileIndex {
        val at = profiles.indexOfFirst { it.id == id }
        if (at < 0) return this
        val rest = profiles.filter { it.id != id }
        val next = if (current != id) current else rest.getOrNull(at)?.id ?: rest.lastOrNull()?.id
        return ProfileIndex(rest, next)
    }

    fun withCurrent(id: String): ProfileIndex = if (find(id) == null) this else copy(current = id)
}

/** Profile colours, handed out in this order (the mockups' palette). */
val PROFILE_COLORS = listOf("green", "orange", "purple", "blue", "crimson", "grey")

/**
 * [color] as one of [PROFILE_COLORS]: names stored by the first profiles
 * build that are no longer offered map to the nearest one, anything else
 * to grey.
 */
fun profileColor(color: String): String = when (color) {
    in PROFILE_COLORS -> color
    "teal" -> "green"
    "red" -> "crimson"
    else -> "grey"
}

/** A profile name as stored: trimmed; null when nothing is left. */
fun profileName(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

/** The first colour no profile uses yet; past the palette, round again. */
fun nextProfileColor(existing: List<Profile>): String =
    PROFILE_COLORS.firstOrNull { c -> existing.none { it.color == c } } ?: PROFILE_COLORS[existing.size % PROFILE_COLORS.size]

/** A new profile's name: the server's host, as the owner knows the server. */
fun defaultProfileName(controlUrl: String): String =
    runCatching { URI(controlUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: controlUrl.trim()

/** The Keystore key of v1's single enrollment; the profile migrated from it keeps using it. */
const val LEGACY_KEY_ALIAS = "frpjump-credentials"

/** Every other profile seals its credentials with a key of its own. */
fun profileKeyAlias(id: String) = "frpjump-profile-$id"
