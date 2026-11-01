package ru.adsrv.frpjump.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.api.Age
import ru.adsrv.frpjump.api.AgeUnit
import ru.adsrv.frpjump.devices.DeviceRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The resource that says an [Age]: a string, or plurals taking [count]. */
data class AgeWords(val id: Int, val plural: Boolean, val count: Int = 0)

fun ageWords(age: Age): AgeWords = when (age.unit) {
    AgeUnit.NOW -> AgeWords(R.string.age_just_now, plural = false)
    AgeUnit.SECONDS -> AgeWords(R.plurals.age_seconds, plural = true, age.count)
    AgeUnit.MINUTES -> AgeWords(R.plurals.age_minutes, plural = true, age.count)
    AgeUnit.HOURS -> AgeWords(R.plurals.age_hours, plural = true, age.count)
    AgeUnit.DAYS -> AgeWords(R.plurals.age_days, plural = true, age.count)
}

/** "just now", "5 min ago", "3 days ago" in the app's language. */
@Composable
@ReadOnlyComposable
fun ageText(age: Age): String {
    val words = ageWords(age)
    return if (words.plural) pluralStringResource(words.id, words.count, words.count) else stringResource(words.id)
}

/**
 * "14:02" today, "30 Oct 14:02" ("30 окт. 14:02") otherwise: in the phone's
 * zone, the app's language and the phone's 12/24-hour setting.
 */
@Composable
fun shortTime(at: Instant): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val local = at.atZone(ZoneId.systemDefault())
    val time = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
    val skeleton = if (local.toLocalDate() == LocalDate.now(local.zone)) time else "dMMM$time"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(local)
}

/**
 * A device online as of a list the server gave earlier: "Was online 2 min
 * ago", counting from when it was last seen, so the words say how old
 * the news is. Nothing more than "Was online" is known without that time.
 * A list restored at start that the server has not been asked about yet
 * says "Checking…": its age is the app's absence, not the device's.
 */
@Composable
@ReadOnlyComposable
fun wasOnline(device: DeviceRow): String = when {
    device.checking -> stringResource(R.string.device_checking)
    else -> device.lastSeen?.let { stringResource(R.string.device_was_online_ago, ageText(it)) } ?: stringResource(R.string.device_was_online)
}
