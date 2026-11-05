package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import ru.adsrv.frpjump.ui.AppTheme

/** Presentation-only policy: no links, image loads, HTML execution or code unescaping. */
internal val assistantMarkdownAnnotator: MarkdownAnnotator = markdownAnnotator(annotate = { content, node ->
    when (node.type) {
        MarkdownElementTypes.CODE_SPAN -> {
            val first = node.children.firstOrNull()
            val last = node.children.lastOrNull()
            if (first != null && last != null && first !== last) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
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

@Composable
internal fun AssistantAnswerMarkdown(answer: String) {
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val references = remember(answer) { ReferenceLinkHandlerImpl() }
    val markdown = rememberMarkdownState(answer, flavour = flavour, parser = parser, referenceLinkHandler = references)
    val state by markdown.state.collectAsState()
    AssistantAnswerMarkdown(answer, state)
}

/** State overload also exercises the library's parse-error fallback in component tests. */
@Composable
internal fun AssistantAnswerMarkdown(answer: String, state: State) {
    val ink = AppTheme.colors.ink
    val mono = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, color = ink)
    lateinit var components: MarkdownComponents
    components = markdownComponents(
        codeFence = { Text(assistantFenceText(it.content, it.node), style = mono) },
        codeBlock = { Text(it.content.substring(it.node.startOffset, it.node.endOffset), style = mono) },
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
    Markdown(
        state = state,
        modifier = Modifier.fillMaxWidth().testTag("assistant_markdown_rendered"),
        colors = markdownColor(text = ink),
        typography = markdownTypography(code = mono, inlineCode = mono),
        annotator = assistantMarkdownAnnotator,
        imageTransformer = NoOpImageTransformerImpl(),
        components = components,
        loading = { Text(answer, modifier = Modifier.testTag("assistant_markdown_loading"), color = ink) },
        error = { Text(answer, color = ink) },
    )
}
