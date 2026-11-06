package ru.adsrv.frpjump.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.intellij.markdown.MarkdownElementTypes as Types
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.ImageGeneratingProvider
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.InlineLinkGeneratingProvider
import org.intellij.markdown.html.ReferenceLinksGeneratingProvider
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.parser.Parser

/** Reference resolution uses the same pinned JetBrains parser as answer rendering, not a second grammar. */
internal fun markdownWebReferences(text: String, url: String): Pair<List<ChatSource>, List<ChatSource>> {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    val inline = InlineLinkGeneratingProvider(null)
    val map = LinkMap.buildLinkMap(tree, text)
    val references = ReferenceLinksGeneratingProvider(map, null)
    val imageProvider = ImageGeneratingProvider(map, null)
    val base = url.toHttpUrl()
    val links = LinkedHashMap<String, ChatSource>()
    val images = LinkedHashMap<String, ChatSource>()
    val linkTypes = setOf(Types.INLINE_LINK, Types.FULL_REFERENCE_LINK, Types.SHORT_REFERENCE_LINK)
    fun add(destination: String, caption: String, image: Boolean) {
        val resolved = runCatching { PublicWebPolicy.url(base.resolve(Parser.unescapeEntities(destination, false))?.toString() ?: return).toString() }.getOrNull() ?: return
        val target = if (image) images else links
        val cap = if (image) 20 else 100
        if (target.size <= cap) target.putIfAbsent(resolved, ChatSource(if (caption.length <= 256) caption else clip(caption, 255) + "…", resolved))
    }
    fun collect(node: ASTNode, image: Boolean) {
        val info = (when {
            image -> imageProvider.getRenderInfo(text, node)
            node.type == Types.INLINE_LINK -> inline.getRenderInfo(text, node)
            else -> references.getRenderInfo(text, node)
        }) ?: return
        val caption = info.label.children.drop(1).dropLast(1).joinToString("") { text.substring(it.startOffset, it.endOffset) }
        add(info.destination.toString(), caption, image)
    }
    val pending = ArrayDeque<ASTNode>().apply { add(tree) }
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        when (node.type) {
            Types.CODE_FENCE, Types.CODE_BLOCK, Types.CODE_SPAN, Types.LINK_DEFINITION -> continue
            Types.IMAGE -> { collect(node, true); continue }
            MarkdownTokenTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> {
                val destination = text.substring(node.startOffset, node.endOffset)
                add(destination, destination, false)
            }
            in linkTypes -> collect(node, false)
        }
        node.children.asReversed().forEach(pending::addLast)
    }
    // One extra unique reference per kind is retained solely to signal clipping using the common page policy.
    return links.values.toList() to images.values.toList()
}
