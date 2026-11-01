package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/** The extra rows wrap a group into even lines: no single key left alone on the last one. */
class BalancedLinesTest {
    @Test
    fun fitsOnOneLine() {
        assertEquals(listOf(7), balancedLines(7, 7))
        assertEquals(listOf(8), balancedLines(8, 15))
    }

    @Test
    fun wrapsEvenly() {
        assertEquals(listOf(4, 3), balancedLines(7, 6))
        assertEquals(listOf(4, 4), balancedLines(8, 7))
        assertEquals(listOf(3, 3, 2), balancedLines(8, 3))
    }

    @Test
    fun degenerateWidths() {
        assertEquals(emptyList<Int>(), balancedLines(0, 7))
        assertEquals(listOf(1, 1), balancedLines(2, 0))
    }
}
