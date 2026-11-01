package ru.adsrv.frpjump.api

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** A device counts as online when its last heartbeat is this recent (as `devices list` does). */
val ONLINE_WINDOW: Duration = Duration.ofSeconds(30)

/**
 * The server stores timestamps in SQLite, which drops the timezone: they come
 * back naive (`2026-10-30T12:00:00.123456`) but are UTC. An explicit offset,
 * if one ever appears, is honoured. Null when the text is not a timestamp.
 */
fun parseServerTime(text: String): Instant? = try {
    val parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(text, OffsetDateTime::from, LocalDateTime::from)
    when (parsed) {
        is OffsetDateTime -> parsed.toInstant()
        is LocalDateTime -> parsed.toInstant(ZoneOffset.UTC)
        else -> null
    }
} catch (_: DateTimeParseException) {
    null
}

/** How long ago, in the unit it is said in: "just now", "12 s ago", "5 min ago", "2 h ago", "3 d ago". */
data class Age(val unit: AgeUnit, val count: Int)

enum class AgeUnit { NOW, SECONDS, MINUTES, HOURS, DAYS }

/** [lastSeen] null: never seen. */
data class Presence(val online: Boolean, val lastSeen: Age?)

/**
 * Online when last seen within [ONLINE_WINDOW] of [now] either way: a server
 * clock slightly ahead of the phone's must not make a live device look offline.
 */
fun presenceOf(lastSeenAt: String?, now: Instant): Presence {
    val seen = lastSeenAt?.let(::parseServerTime) ?: return Presence(online = false, lastSeen = null)
    val age = Duration.between(seen, now)
    return Presence(online = age.abs() <= ONLINE_WINDOW, lastSeen = relativeAge(age))
}

fun relativeAge(age: Duration): Age {
    val seconds = age.seconds
    return when {
        seconds < 5 -> Age(AgeUnit.NOW, 0)
        seconds < 60 -> Age(AgeUnit.SECONDS, seconds.toInt())
        seconds < 3600 -> Age(AgeUnit.MINUTES, (seconds / 60).toInt())
        seconds < 86400 -> Age(AgeUnit.HOURS, (seconds / 3600).toInt())
        else -> Age(AgeUnit.DAYS, (seconds / 86400).toInt())
    }
}
