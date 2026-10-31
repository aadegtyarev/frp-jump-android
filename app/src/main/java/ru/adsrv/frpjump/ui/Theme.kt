package ru.adsrv.frpjump.ui

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
    val onProfile = Color(palette.onProfile)

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

/** The app's own colours, light or dark as the system is (a theme setting comes later). */
@Composable
fun FrpJumpTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
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
