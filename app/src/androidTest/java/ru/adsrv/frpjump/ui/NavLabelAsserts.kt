package ru.adsrv.frpjump.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The bottom-navigation label [label] under the item [tag] is shown whole: one line, no ellipsis, the drawn text within its box and the
 * box within its item. `didOverflowWidth` is not used: for a one-line label (softWrap off) it is true although the text fits (measured:
 * a 114 px line in a 115 px box, flag set), so it would fail every correct bar.
 */
internal fun assertNavLabelWhole(compose: SemanticsNodeInteractionsProvider, label: String, tag: String) {
    val node = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
    val results = mutableListOf<TextLayoutResult>()
    node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    assertTrue("no text layout for '$label'", results.isNotEmpty())
    val r = results.first()
    val ellipsized = (0 until r.lineCount).filter { r.isLineEllipsized(it) }
    assertTrue("'$label' is ellipsized (lines $ellipsized)", ellipsized.isEmpty())
    assertEquals("'$label' is wrapped", 1, r.lineCount)
    assertTrue("'$label' is cut: text ${r.getLineRight(0) - r.getLineLeft(0)} px in a ${r.size.width} px box",
        r.getLineRight(0) <= r.size.width + 0.5f && r.getLineLeft(0) >= -0.5f)
    assertTrue("'$label' is cut vertically", !r.didOverflowHeight)
    val box = node.fetchSemanticsNode().boundsInRoot
    val item = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    assertTrue("'$label' $box leaves its item $item", box.left >= item.left - 0.5f && box.right <= item.right + 0.5f)
}
