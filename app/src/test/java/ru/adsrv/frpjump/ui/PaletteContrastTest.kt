package ru.adsrv.frpjump.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.profiles.PROFILE_COLORS
import kotlin.math.pow

/** WCAG 2.1 contrast of every text/background pair the screens use, in both themes. */
class PaletteContrastTest {
    private fun channel(c: Long, shift: Int): Double {
        val v = ((c shr shift) and 0xFF) / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Long) = 0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0)

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Text: 4.5:1 (AA, normal size). */
    private fun textPairs(p: Palette): Map<String, Pair<Long, Long>> = buildMap {
        for ((bg, name) in listOf(p.ground to "ground", p.card to "card", p.raised to "raised")) {
            put("ink on $name", p.ink to bg)
            put("muted on $name", p.muted to bg)
            put("accent on $name", p.accent to bg)
            put("error on $name", p.error to bg)
            put("warning on $name", p.warning to bg)
        }
        put("ink on tile", p.ink to p.tile)
        put("muted on tile", p.muted to p.tile)
        put("onAccent on accent", p.onAccent to p.accent)
        put("onAccentContainer on accentContainer", p.onAccentContainer to p.accentContainer)
        put("onStrong on strong", p.onStrong to p.strong)
        put("onCode on code", p.onCode to p.code)
        put("onWarningContainer on warningContainer", p.onWarningContainer to p.warningContainer)
        put("onError on error", p.onError to p.error)
        put("onErrorContainer on errorContainer", p.onErrorContainer to p.errorContainer)
        // Snackbars: Material's inverse surface is ink with the ground as text.
        put("ground on ink", p.ground to p.ink)
        PROFILE_COLORS.forEach { put("onProfile on $it", p.onProfile to p.profiles.getValue(it)) }
    }

    /** Marks that carry meaning alone (status dots, profile colours): 3:1 against the card (1.4.11). */
    private fun markPairs(p: Palette): Map<String, Pair<Long, Long>> = buildMap {
        put("online on card", p.online to p.card)
        put("offline on card", p.offline to p.card)
        put("warning on card", p.warning to p.card)
        put("error on card", p.error to p.card)
        put("accent on card", p.accent to p.card)
        PROFILE_COLORS.forEach { put("$it on card", p.profiles.getValue(it) to p.card) }
    }

    private fun check(name: String, p: Palette) {
        val failures = textPairs(p).filter { (_, v) -> contrast(v.first, v.second) < 4.5 }.keys +
            markPairs(p).filter { (_, v) -> contrast(v.first, v.second) < 3.0 }.keys
        assertTrue("$name: below WCAG AA: $failures", failures.isEmpty())
    }

    @Test
    fun `light theme text and marks meet WCAG AA`() = check("light", LightPalette)

    @Test
    fun `dark theme text and marks meet WCAG AA`() = check("dark", DarkPalette)

    @Test
    fun `every profile colour has a shade in both themes`() {
        assertTrue(LightPalette.profiles.keys == PROFILE_COLORS.toSet())
        assertTrue(DarkPalette.profiles.keys == PROFILE_COLORS.toSet())
    }

    @Test
    fun `dark is graphite, not black, and its accent is toned down`() {
        assertTrue(luminance(DarkPalette.ground) > luminance(0xFF000000) + 0.005)
        // Warm: red above blue in the ground.
        assertTrue((DarkPalette.ground shr 16 and 0xFF) > (DarkPalette.ground and 0xFF))
        assertTrue(contrast(DarkPalette.accent, DarkPalette.card) < contrast(DarkPalette.ink, DarkPalette.card))
    }
}
