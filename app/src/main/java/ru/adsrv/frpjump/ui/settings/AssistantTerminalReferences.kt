package ru.adsrv.frpjump.ui.settings

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolReference
import ru.adsrv.frpjump.assistant.chat.ChatToolReceipt

internal data class ReferenceAnswer(val text: String, val references: List<ToolReference>)

/** Only an issued token occupying a top-level paragraph becomes an action. */
internal fun referenceAnswer(answer: String, receipts: List<ChatToolReceipt>): ReferenceAnswer {
    val issued = receipts.flatMap { receipt ->
        receipt.result?.takeIf { it.outcome == ToolOutcome.SUCCESS && it.name in setOf("terminal_tabs", "terminal_open") }
            ?.references.orEmpty()
    }.associateBy { it.token }
    if (issued.isEmpty()) return ReferenceAnswer(answer, emptyList())

    val blocks = runCatching { MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(answer).children }
        .getOrElse { return ReferenceAnswer(answer, emptyList()) }
    val matches = blocks.filter { it.type == MarkdownElementTypes.PARAGRAPH }.mapNotNull { block ->
        issued[answer.substring(block.startOffset, block.endOffset)]?.let { block to it }
    }
    if (matches.isEmpty()) return ReferenceAnswer(answer, emptyList())

    val text = StringBuilder(answer)
    for ((block, _) in matches.asReversed()) text.delete(block.startOffset, block.endOffset)
    return ReferenceAnswer(text.toString(), matches.map { it.second }.distinctBy { it.token })
}
