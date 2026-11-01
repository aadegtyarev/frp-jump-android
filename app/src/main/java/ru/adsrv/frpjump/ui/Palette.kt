package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.profiles.PROFILE_COLORS

/**
 * One theme's colours as ARGB values, from the mockups. Each theme is
 * designed on its own: dark is a warm graphite with a toned-down accent,
 * its own status and profile colours, not an inversion of light. Text
 * pairs keep at least WCAG AA contrast (see the palette test).
 */
data class Palette(
    /** The screen behind the cards. */
    val ground: Long,
    /** Cards, sheets, the switcher. */
    val card: Long,
    /** Tiles and fields inside a card. */
    val tile: Long,
    /** Dialogs and menus over the cards (dark lifts them; light keeps them white). */
    val raised: Long,
    /** Borders of fields and pills. */
    val outline: Long,
    /** Lines between rows. */
    val divider: Long,
    val ink: Long,
    /** Secondary text. */
    val muted: Long,
    /** Icons that only decorate (chevrons); never text. */
    val faint: Long,
    val accent: Long,
    val onAccent: Long,
    val accentContainer: Long,
    val onAccentContainer: Long,
    /** The main button of a form ("Connect phone"). */
    val strong: Long,
    val onStrong: Long,
    /** A command to type elsewhere. */
    val code: Long,
    val onCode: Long,
    /** Online, direct: dot and text. */
    val online: Long,
    /** Offline: the dot only (the row says it in words). */
    val offline: Long,
    /** Through the server, stale: text and dot. */
    val warning: Long,
    val warningContainer: Long,
    val onWarningContainer: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    /** A file row's glyph on [tile]: an archive (muted amber), an executable (muted green). Folders keep the accent. */
    val archive: Long,
    val executable: Long,
    /** The terminal's bars (title, tabs, snippets, extra keys): dark in both themes, around a black terminal. */
    val termBar: Long,
    /** A key, a selected tab on [termBar]. */
    val termKey: Long,
    /** Lines and chip borders on [termBar]. */
    val termLine: Long,
    val onTerm: Long,
    /** Secondary text on [termBar] and [termKey]. */
    val termMuted: Long,
    /** By [PROFILE_COLORS] name. */
    val profiles: Map<String, Long>,
    /** The letter on a profile's tile. */
    val onProfile: Long,
)

val LightPalette = Palette(
    ground = 0xFFF5F7F6,
    card = 0xFFFFFFFF,
    tile = 0xFFEEF1F0,
    raised = 0xFFFFFFFF,
    outline = 0xFFD7DEDB,
    divider = 0xFFE3E8E6,
    ink = 0xFF14201C,
    muted = 0xFF55645F,
    faint = 0xFF9AA6A2,
    accent = 0xFF0B6E5F,
    onAccent = 0xFFFFFFFF,
    accentContainer = 0xFFDDF1EC,
    onAccentContainer = 0xFF0B6E5F,
    strong = 0xFF14201C,
    onStrong = 0xFFFFFFFF,
    code = 0xFF14201C,
    onCode = 0xFFE6F2EE,
    online = 0xFF1E9E5A,
    // The mockup's #9AA6A2, a step darker: 3:1 against the card, as a lone dot needs.
    offline = 0xFF87938F,
    warning = 0xFF9A5B0A,
    warningContainer = 0xFFFCEFD9,
    onWarningContainer = 0xFF8A4B06,
    error = 0xFFB42318,
    onError = 0xFFFFFFFF,
    errorContainer = 0xFFFDECEA,
    onErrorContainer = 0xFFB42318,
    archive = 0xFF96681F,
    executable = 0xFF55803A,
    termBar = 0xFF17211E,
    termKey = 0xFF26302D,
    termLine = 0xFF33403C,
    onTerm = 0xFFD7E4DF,
    termMuted = 0xFFA9B8B3,
    profiles = mapOf(
        "green" to 0xFF0B6E5F,
        // The mockup's #C2570C is a hair under 4.5:1 with white.
        "orange" to 0xFFB8520B,
        "purple" to 0xFF6D3FC0,
        "blue" to 0xFF1F5FBF,
        "crimson" to 0xFFB42357,
        "grey" to 0xFF5B6560,
    ),
    onProfile = 0xFFFFFFFF,
)

val DarkPalette = Palette(
    ground = 0xFF1C1B19,
    card = 0xFF262523,
    tile = 0xFF33312E,
    raised = 0xFF2F2D2A,
    outline = 0xFF4D4A45,
    divider = 0xFF393734,
    ink = 0xFFECE8E1,
    muted = 0xFFADA79E,
    faint = 0xFF7A756D,
    accent = 0xFF5DB3A2,
    onAccent = 0xFF0F1F1B,
    accentContainer = 0xFF213B35,
    onAccentContainer = 0xFFA6DCCF,
    strong = 0xFFD6D0C6,
    onStrong = 0xFF1C1B19,
    code = 0xFF141311,
    onCode = 0xFFD9EFE9,
    online = 0xFF5CC08A,
    offline = 0xFF878177,
    warning = 0xFFE3A857,
    warningContainer = 0xFF3D3020,
    onWarningContainer = 0xFFF0C98F,
    error = 0xFFEF8A7E,
    onError = 0xFF2A0E0B,
    errorContainer = 0xFF4A2622,
    onErrorContainer = 0xFFF7B9B0,
    archive = 0xFFCFA562,
    executable = 0xFFA3C585,
    termBar = 0xFF1A1917,
    termKey = 0xFF302E2B,
    termLine = 0xFF3F3C38,
    onTerm = 0xFFE6E1D9,
    termMuted = 0xFFADA79E,
    profiles = mapOf(
        "green" to 0xFF5DB3A2,
        "orange" to 0xFFE39058,
        "purple" to 0xFFAE92E6,
        "blue" to 0xFF79A6EC,
        "crimson" to 0xFFE77A9F,
        "grey" to 0xFFA39E95,
    ),
    onProfile = 0xFF1C1B19,
)
