package ru.adsrv.frpjump.locale

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Settings › Language. [tag] is a BCP 47 language tag; empty follows the system. */
@Serializable
enum class AppLanguage(val tag: String) {
    @SerialName("system") SYSTEM(""),
    @SerialName("ru") RUSSIAN("ru"),
    @SerialName("en") ENGLISH("en"),
    ;

    companion object {
        /** The language a per-app locale list stands for; an unknown one (set elsewhere) as [SYSTEM]. */
        fun ofTags(tags: String): AppLanguage {
            val first = tags.split(',').firstOrNull()?.trim().orEmpty().substringBefore('-').lowercase()
            return entries.firstOrNull { it != SYSTEM && it.tag == first } ?: SYSTEM
        }
    }
}

/**
 * The app's own language, as Android 13+ keeps it per app (also set from the
 * system's app settings) or AppCompat on older versions. Main thread.
 */
fun currentAppLanguage(): AppLanguage = AppLanguage.ofTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

/**
 * Switches the app to [language] at once: Android 13+ stores it and
 * recreates the activity; on older versions AppCompat does both. Main thread.
 */
fun applyAppLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
}

/**
 * Before any activity on Android 10–12, AppCompat has not read back the
 * language it stored yet: [stored] (the app's settings) stands in, so that
 * notifications of a process started in the background speak it too.
 * Android 13+ applies the per-app language to the whole process by itself.
 */
fun restoreAppLanguage(stored: AppLanguage) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    if (stored != AppLanguage.SYSTEM && AppCompatDelegate.getApplicationLocales().isEmpty) applyAppLanguage(stored)
}

/**
 * [this] context with the app's language. Android 13+ gives every context
 * of the app its language; before that AppCompat only changes activities,
 * so the application, services and receivers need it applied here.
 */
fun Context.localized(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
    return createConfigurationContext(config)
}
