package ru.adsrv.frpjump.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.profiles.profileColor
import ru.adsrv.frpjump.settings.ThemeMode

/** The app's colours beyond Material's roles: status and profile colours, cards and tiles. */
@Immutable
class AppColors(private val palette: Palette) {
    val ground = Color(palette.ground)
    val card = Color(palette.card)
    val tile = Color(palette.tile)
    val outline = Color(palette.outline)
    val divider = Color(palette.divider)
    val ink = Color(palette.ink)
    val muted = Color(palette.muted)
    val faint = Color(palette.faint)
    val accent = Color(palette.accent)
    val onAccent = Color(palette.onAccent)
    val accentContainer = Color(palette.accentContainer)
    val onAccentContainer = Color(palette.onAccentContainer)
    val strong = Color(palette.strong)
    val onStrong = Color(palette.onStrong)
    val code = Color(palette.code)
    val onCode = Color(palette.onCode)
    val online = Color(palette.online)
    val offline = Color(palette.offline)
    val warning = Color(palette.warning)
    val warningContainer = Color(palette.warningContainer)
    val onWarningContainer = Color(palette.onWarningContainer)
    val error = Color(palette.error)
    val errorContainer = Color(palette.errorContainer)
    val onErrorContainer = Color(palette.onErrorContainer)
    val archive = Color(palette.archive)
    val executable = Color(palette.executable)
    val onProfile = Color(palette.onProfile)
    val termBar = Color(palette.termBar)
    val termKey = Color(palette.termKey)
    val termLine = Color(palette.termLine)
    val onTerm = Color(palette.onTerm)
    val termMuted = Color(palette.termMuted)

    /** Ended or failed on the terminal's dark bars, in either theme. */
    val termError = Color(DarkPalette.error)

    /** A profile's colour on the terminal's dark bars, in either theme. */
    fun termProfile(name: String): Color = Color(DarkPalette.profiles.getValue(profileColor(name)))

    /** A profile's colour in this theme, by its stored name. */
    fun profile(name: String): Color = Color(palette.profiles.getValue(profileColor(name)))
}

private val LocalAppColors = staticCompositionLocalOf { AppColors(LightPalette) }

/** The current theme's [AppColors]. */
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Whether [mode] is dark now; [ThemeMode.SYSTEM] reads the configuration. */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Settings › Theme for what Compose does not draw: the window behind it,
 * the system's dialogs (the fingerprint prompt) and the terminal's and web
 * view's resources. AppCompat keeps it for the process only, so the app sets
 * it again on every start. The activity handles uiMode itself: not recreated.
 */
fun applyTheme(mode: ThemeMode) {
    AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        },
    )
}

/** The app's own colours, light or dark as Settings › Theme says (the system's by default). */
@Composable
fun FrpJumpTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val palette = if (isDark(mode)) DarkPalette else LightPalette
    CompositionLocalProvider(LocalAppColors provides AppColors(palette)) {
        MaterialTheme(colorScheme = colorScheme(palette, dark = palette === DarkPalette), shapes = AppShapes, content = content)
    }
}

/** Material's roles from [p]: screens on the ground, menus and dialogs raised, the accent as primary. */
private fun colorScheme(p: Palette, dark: Boolean): ColorScheme {
    val c = { v: Long -> Color(v) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(p.accent),
        onPrimary = c(p.onAccent),
        primaryContainer = c(p.accentContainer),
        onPrimaryContainer = c(p.onAccentContainer),
        inversePrimary = c(if (dark) LightPalette.accent else DarkPalette.accent),
        secondary = c(p.accent),
        onSecondary = c(p.onAccent),
        secondaryContainer = c(p.accentContainer),
        onSecondaryContainer = c(p.onAccentContainer),
        tertiary = c(p.accent),
        onTertiary = c(p.onAccent),
        tertiaryContainer = c(p.accentContainer),
        onTertiaryContainer = c(p.onAccentContainer),
        background = c(p.ground),
        onBackground = c(p.ink),
        surface = c(p.ground),
        onSurface = c(p.ink),
        surfaceVariant = c(p.tile),
        onSurfaceVariant = c(p.muted),
        surfaceTint = c(p.accent),
        inverseSurface = c(p.ink),
        inverseOnSurface = c(p.ground),
        error = c(p.error),
        onError = c(p.onError),
        errorContainer = c(p.errorContainer),
        onErrorContainer = c(p.onErrorContainer),
        outline = c(p.outline),
        outlineVariant = c(p.divider),
        surfaceBright = c(p.card),
        surfaceDim = c(p.ground),
        surfaceContainerLowest = c(p.card),
        surfaceContainerLow = c(p.raised),
        surfaceContainer = c(p.raised),
        surfaceContainerHigh = c(p.raised),
        surfaceContainerHighest = c(p.tile),
    )
}
