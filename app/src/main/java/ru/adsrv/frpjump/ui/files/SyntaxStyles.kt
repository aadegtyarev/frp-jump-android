package ru.adsrv.frpjump.ui.files

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import ru.adsrv.frpjump.files.text.Token
import ru.adsrv.frpjump.ui.AppColors

/**
 * The viewer's colours for each [Token], from the theme's own colours (on
 * the card's background, where they keep AA contrast in light and dark):
 * no colour of its own is added for code.
 */
@Immutable
class SyntaxStyles(colors: AppColors) {
    private val styles: Array<SpanStyle> = Token.entries.map { token ->
        when (token) {
            Token.KEYWORD -> SpanStyle(color = colors.profile("purple"))
            Token.STRING -> SpanStyle(color = colors.executable)
            Token.NUMBER -> SpanStyle(color = colors.profile("blue"))
            Token.LITERAL -> SpanStyle(color = colors.profile("orange"))
            Token.COMMENT -> SpanStyle(color = colors.muted, fontStyle = FontStyle.Italic)
            Token.KEY -> SpanStyle(color = colors.accent)
            Token.PUNCT -> SpanStyle(color = colors.muted)
            Token.VARIABLE -> SpanStyle(color = colors.profile("crimson"))
            Token.SECTION -> SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)
            Token.ANCHOR -> SpanStyle(color = colors.profile("orange"))
            Token.HEADING -> SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)
            Token.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
            Token.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
            Token.CODE -> SpanStyle(color = colors.executable)
            Token.LINK -> SpanStyle(color = colors.profile("blue"), textDecoration = TextDecoration.Underline)
            Token.MARKER -> SpanStyle(color = colors.archive, fontWeight = FontWeight.Bold)
            Token.TIME -> SpanStyle(color = colors.profile("blue"))
            Token.LEVEL_ERROR -> SpanStyle(color = colors.error, fontWeight = FontWeight.Bold)
            Token.LEVEL_WARN -> SpanStyle(color = colors.warning, fontWeight = FontWeight.Bold)
            Token.LEVEL_QUIET -> SpanStyle(color = colors.muted)
        }
    }.toTypedArray()

    /** A match of the search; [current] the one stepped to. */
    val match = SpanStyle(background = colors.accentContainer, color = colors.onAccentContainer)
    val currentMatch = SpanStyle(background = colors.accent, color = colors.onAccent)

    /** The `↪` of a row that goes on a long line. */
    val continuation = SpanStyle(color = colors.muted)

    fun of(ordinal: Int): SpanStyle = styles[ordinal]
}
