package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.DarkPalette

/** Presentation-only policy: no links, image loads, HTML execution or code unescaping. */
internal val assistantMarkdownAnnotator: MarkdownAnnotator = assistantAnnotator(Color.Unspecified)

internal fun assistantAnnotator(codeBackground: Color): MarkdownAnnotator = markdownAnnotator(annotate = { content, node ->
    when (node.type) {
        MarkdownElementTypes.CODE_SPAN -> {
            val first = node.children.firstOrNull()
            val last = node.children.lastOrNull()
            if (first != null && last != null && first !== last) {
                // Inline code (the mockups' `code`): monospace a little smaller than the body, no letter spacing of its own, a quiet
                // background and no accent colour (the text keeps the answer's colour). A span background is plain: no rounded corners.
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.86.em, letterSpacing = 0.sp, background = codeBackground))
                append(content.substring(first.endOffset, last.startOffset))
                pop()
            } else append(content.substring(node.startOffset, node.endOffset))
            true
        }
        MarkdownElementTypes.IMAGE, MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK,
        MarkdownTokenTypes.AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK, MarkdownTokenTypes.HTML_TAG -> {
            append(content.substring(node.startOffset, node.endOffset))
            true
        }
        else -> false
    }
}, config = markdownAnnotatorConfig(eolAsNewLine = true))

/** Extract only the library's parsed fenced-code payload, without reindenting commands. */
internal fun assistantFenceText(content: String, node: ASTNode): String {
    val openingLineEnd = node.children.firstOrNull { it.type == MarkdownTokenTypes.EOL }
        ?: return content.substring(node.startOffset, node.endOffset)
    val closing = node.children.lastOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_END }
    return content.substring(openingLineEnd.endOffset, closing?.startOffset ?: node.endOffset)
}

private val tableDelimiterLine = Regex("""^ {0,3}\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t\r]*$""")

private fun tableCellCount(line: String): Int {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
    return body.split(Regex("(?<!\\\\)\\|")).size
}

/**
 * Display copy of an answer. The library's parser starts a GFM table only at the start of a block, but models often
 * write "text:" and the table on the next line. A blank line is inserted before such a table header (never inside a
 * fenced block); everything else is untouched. Stored turns and provider requests keep the original text.
 */
internal fun assistantDisplayMarkdown(answer: String): String {
    if (!answer.contains('|') || !answer.contains("-")) return answer
    val lines = answer.split('\n')
    val out = ArrayList<String>(lines.size + 2)
    var fence: String? = null
    var changed = false
    for (line in lines) {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        if (indent <= 3 && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
            val mark = trimmed.take(3)
            if (fence == null) fence = mark else if (fence == mark) fence = null
            out.add(line); continue
        }
        if (fence == null && out.size >= 2 && line.contains('|') && tableDelimiterLine.matches(line)) {
            val header = out[out.size - 1]
            val before = out[out.size - 2]
            if (header.contains('|') && header.isNotBlank() && before.isNotBlank() &&
                tableCellCount(header) == tableCellCount(line)) {
                out.add(out.size - 1, ""); changed = true
            }
        }
        out.add(line)
    }
    return if (changed) out.joinToString("\n") else answer
}

internal enum class AssistantColumnAlignment { START, CENTER, END }

/** Cells of a parsed GFM table in row-major order; every row is padded to [columns] cells. */
internal class AssistantTableModel(
    val header: List<ASTNode?>,
    val rows: List<List<ASTNode?>>,
    val alignments: List<AssistantColumnAlignment>,
) {
    val columns: Int get() = header.size
}

internal fun assistantTableModel(content: String, table: ASTNode): AssistantTableModel {
    fun cells(row: ASTNode) = row.children.filter { it.type == GFMTokenTypes.CELL }
    val header = table.children.firstOrNull { it.type == GFMElementTypes.HEADER }?.let(::cells).orEmpty()
    val body = table.children.filter { it.type == GFMElementTypes.ROW }.map(::cells)
    val delimiter = table.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        ?.let { content.substring(it.startOffset, it.endOffset) }.orEmpty()
    val marks = delimiter.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val columns = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0, 1)
    fun <T> List<T>.padded(): List<T?> = List(columns) { getOrNull(it) }
    val alignments = List(columns) { column ->
        val mark = marks.getOrNull(column).orEmpty()
        when {
            mark.startsWith(":") && mark.endsWith(":") && mark.length > 1 -> AssistantColumnAlignment.CENTER
            mark.endsWith(":") -> AssistantColumnAlignment.END
            else -> AssistantColumnAlignment.START
        }
    }
    return AssistantTableModel(header.padded(), body.map { it.padded() }, alignments)
}

private fun AnnotatedString.trimmed(): AnnotatedString {
    var start = 0
    var end = length
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    return subSequence(start, end)
}

/**
 * A GFM table: a block as wide as its text needs (a cell wraps only past [maxCell]) that scrolls horizontally inside
 * its own area. Cells are laid out row by row, so TalkBack reads them in that order; none is ellipsized.
 */
@Composable
private fun AssistantTable(content: String, node: ASTNode, style: TextStyle, annotator: MarkdownAnnotator) {
    val colors = AppTheme.colors
    val model = remember(content, node) { assistantTableModel(content, node) }
    val settings = remember(annotator) { DefaultAnnotatorSettings(androidx.compose.ui.text.TextLinkStyles(), SpanStyle(), annotator) }
    val maxCell = 260.dp
    val rowCount = model.rows.size + 1
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("assistant_table")) {
        Layout(
            modifier = Modifier.semantics { collectionInfo = CollectionInfo(rowCount, model.columns) },
            content = {
                for ((rowIndex, row) in (listOf(model.header) + model.rows).withIndex()) {
                    for ((column, cell) in row.withIndex()) {
                        val text = remember(content, cell) {
                            if (cell == null) AnnotatedString("")
                            else content.buildMarkdownAnnotatedString(cell, style, settings).trimmed()
                        }
                        Box(
                            Modifier
                                .semantics { collectionItemInfo = CollectionItemInfo(rowIndex, 1, column, 1) }
                                .then(if (rowIndex == 0) Modifier.background(colors.tile) else Modifier)
                                .border(1.dp, colors.outline)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text,
                                style = style,
                                color = colors.ink,
                                fontWeight = if (rowIndex == 0) FontWeight.SemiBold else null,
                                textAlign = when (model.alignments[column]) {
                                    AssistantColumnAlignment.START -> TextAlign.Start
                                    AssistantColumnAlignment.CENTER -> TextAlign.Center
                                    AssistantColumnAlignment.END -> TextAlign.End
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val columns = model.columns
            val cap = maxCell.roundToPx()
            val widths = IntArray(columns) { 1 }
            measurables.forEachIndexed { index, cell ->
                val column = index % columns
                widths[column] = maxOf(widths[column], minOf(cell.maxIntrinsicWidth(Constraints.Infinity), cap))
            }
            val heights = IntArray(rowCount)
            measurables.forEachIndexed { index, cell ->
                val row = index / columns
                heights[row] = maxOf(heights[row], cell.minIntrinsicHeight(widths[index % columns]))
            }
            val placeables = measurables.mapIndexed { index, cell ->
                val column = index % columns
                cell.measure(Constraints.fixed(widths[column], heights[index / columns]))
            }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (row in 0 until rowCount) {
                    var x = 0
                    for (column in 0 until columns) {
                        placeables[row * columns + column].placeRelative(x, y)
                        x += widths[column]
                    }
                    y += heights[row]
                }
            }
        }
    }
}

/** Code that keeps its own lines: scrolls horizontally instead of wrapping, on the app's tile colour. */
@Composable
private fun AssistantCode(text: String, style: TextStyle) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppTheme.colors.tile)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(text, style = style, softWrap = false) }
}

@Composable
internal fun AssistantAnswerMarkdown(answer: String) {
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val references = remember(answer) { ReferenceLinkHandlerImpl() }
    val display = remember(answer) { assistantDisplayMarkdown(answer) }
    val markdown = rememberMarkdownState(display, flavour = flavour, parser = parser, referenceLinkHandler = references)
    val state by markdown.state.collectAsState()
    AssistantAnswerMarkdown(answer, state)
}

/** State overload also exercises the library's parse-error fallback in component tests. */
@Composable
internal fun AssistantAnswerMarkdown(answer: String, state: State) {
    val ink = AppTheme.colors.ink
    val tile = AppTheme.colors.tile
    val typo = MaterialTheme.typography
    val mono = typo.bodyLarge.copy(fontFamily = FontFamily.Monospace, color = ink)
    // Dark: the card colour over the chat's ground; light: the tile, a step darker than the ground (the white card would hardly show).
    val inlineCodeBackground = if (AppTheme.colors.palette === DarkPalette) AppTheme.colors.card else tile
    val annotator = remember(inlineCodeBackground) { assistantAnnotator(inlineCodeBackground) }
    lateinit var components: MarkdownComponents
    components = markdownComponents(
        codeFence = { AssistantCode(assistantFenceText(it.content, it.node), mono) },
        codeBlock = { AssistantCode(it.content.substring(it.node.startOffset, it.node.endOffset), mono) },
        table = { AssistantTable(it.content, it.node, typo.bodyLarge, annotator) },
        image = { Text(it.content.substring(it.node.startOffset, it.node.endOffset), color = ink) },
        custom = { type, element ->
            if (type == MarkdownElementTypes.HTML_BLOCK) {
                Text(element.content.substring(element.node.startOffset, element.node.endOffset), color = ink)
            } else {
                element.node.children.forEach { child ->
                    MarkdownElement(child, components, element.content, includeSpacer = false)
                }
            }
        },
    )
    val bold = typo.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    Markdown(
        state = state,
        modifier = Modifier.fillMaxWidth().testTag("assistant_markdown_rendered"),
        colors = markdownColor(text = ink, codeBackground = tile, inlineCodeBackground = inlineCodeBackground, dividerColor = AppTheme.colors.outline),
        typography = markdownTypography(
            h1 = typo.titleLarge, h2 = typo.titleMedium, h3 = typo.titleSmall, h4 = bold, h5 = bold, h6 = bold,
            code = mono, inlineCode = mono,
        ),
        annotator = annotator,
        imageTransformer = NoOpImageTransformerImpl(),
        components = components,
        loading = { Text(answer, modifier = Modifier.testTag("assistant_markdown_loading"), color = ink) },
        error = { Text(answer, color = ink) },
    )
}
