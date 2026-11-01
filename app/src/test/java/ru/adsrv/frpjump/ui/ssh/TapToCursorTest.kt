package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapToCursorTest {
    /**
     * Screen rows from the top (row 0); a row ending in `\` wraps into the
     * next (the `\` itself is not a cell). `<>` is one wide character:
     * its left and right halves.
     */
    private fun grid(columns: Int, vararg rows: String) = object : TapGrid {
        private val cells = rows.map { it.removeSuffix("\\").padEnd(columns) }
        override val columns = columns
        override val firstRow = 0
        override val lastRow = rows.size - 1
        override fun wraps(row: Int) = rows[row].endsWith("\\")
        override fun isWideTail(row: Int, column: Int) = cells[row][column] == '>'
        override fun isBlank(row: Int, column: Int) = cells[row][column] == ' '
        override fun text(row: Int, column: Int) = when (val c = cells[row][column]) {
            '>' -> ""
            '<' -> "W"
            else -> c.toString()
        }
    }

    @Test
    fun `a tap on the cursor's row moves by the columns between`() {
        val g = grid(20, "$ echo hello world")
        // Cursor at the end (column 18); a tap on the "h" of hello (column 7).
        assertEquals(-11, tapCursorMoves(g, 0, 18, 0, 7))
        assertEquals(5, tapCursorMoves(g, 0, 2, 0, 7))
        assertEquals(0, tapCursorMoves(g, 0, 7, 0, 7))
    }

    @Test
    fun `wrapped rows are one line`() {
        val g = grid(10, "$ echo aaa\\", "bbbbbbbbbb\\", "ccc")
        // Cursor after "ccc" (row 2, column 3); a tap on row 0, column 7: 10 + 10 + 3 - 7 back.
        assertEquals(-16, tapCursorMoves(g, 2, 3, 0, 7))
        // Cursor on row 0, a tap on row 1, column 4.
        assertEquals(10 + 4 - 2, tapCursorMoves(g, 0, 2, 1, 4))
    }

    @Test
    fun `rows not wrapped into the cursor's are not its line`() {
        val g = grid(10, "output", "$ ls\\", "-la", "")
        assertNull(tapCursorMoves(g, 2, 3, 0, 2))
        assertNull(tapCursorMoves(g, 2, 3, 3, 0))
        assertNull(tapCursorMoves(g, 2, 3, 7, 0))
        // A row the shell broke with a real line break is another line, even right above.
        val broken = grid(10, "$ for i in", "> do", "> echo")
        assertNull(tapCursorMoves(broken, 2, 6, 1, 2))
    }

    @Test
    fun `a wide character is one move, its right half lands on it`() {
        val g = grid(12, "$ echo <><>x")
        // "$ echo " is 7 characters, then two wide ones (columns 7-10); the cursor is on "x" (column 11).
        assertEquals(-2, tapCursorMoves(g, 0, 11, 0, 7))
        assertEquals(-2, tapCursorMoves(g, 0, 11, 0, 8))
        assertEquals(-1, tapCursorMoves(g, 0, 11, 0, 9))
        assertEquals(-1, tapCursorMoves(g, 0, 11, 0, 10))
    }

    @Test
    fun `the blank left at a wrap by a wide character that did not fit is not a character`() {
        val g = grid(5, "$ ab \\", "<>c")
        // "$ ab" + wide + "c": the cursor after c (row 1, column 3) is 6 characters in; a tap on "a" (2) is 4 back.
        assertEquals(-4, tapCursorMoves(g, 1, 3, 0, 2))
    }

    @Test
    fun `a tap past the end of the text lands at its end`() {
        val g = grid(20, "$ echo hi")
        // Cursor on "h" (column 7), a tap far right: to just after "i" (column 9).
        assertEquals(2, tapCursorMoves(g, 0, 7, 0, 18))
        // Already at the end: nothing to move.
        assertEquals(0, tapCursorMoves(g, 0, 9, 0, 15))
        // Typed spaces after the text: the cursor is past it and stays.
        assertEquals(0, tapCursorMoves(g, 0, 12, 0, 18))
        // On a wrapped line, past the end of its last row.
        val wrapped = grid(5, "$ abc\\", "de")
        assertEquals(2, tapCursorMoves(wrapped, 1, 0, 1, 4))
    }

    @Test
    fun `a tap before the text moves as far, the shell stops at its start`() {
        val g = grid(20, "root# ls -la")
        assertEquals(-12, tapCursorMoves(g, 0, 12, 0, 0))
    }

    @Test
    fun `the moves are capped`() {
        val rows = Array(300) { "x".repeat(10) + "\\" }
        rows[rows.size - 1] = "x".repeat(10)
        val g = grid(10, *rows)
        assertEquals(-MAX_TAP_MOVES, tapCursorMoves(g, 299, 9, 0, 0))
        assertEquals(MAX_TAP_MOVES, tapCursorMoves(g, 0, 0, 299, 9))
    }

    @Test
    fun `the cursor's column past the row clamps to its last cell`() {
        val g = grid(4, "abcd")
        assertEquals(-3, tapCursorMoves(g, 0, 4, 0, 0))
    }

    @Test
    fun `the cursor's line reads the same after it only re-wrapped`() {
        val wide = grid(20, "output", "$ echo abc defghijkl\\", "mn")
        val narrow = grid(10, "output", "$ echo abc\\", " defghijkl\\", "mn")
        assertEquals("$ echo abc defghijklmn\u000020", cursorLineState(wide, 2, 0))
        assertEquals(cursorLineState(wide, 2, 1), cursorLineState(narrow, 3, 1))
        // Typed, or the cursor moved: not the same.
        assertNotEquals(cursorLineState(wide, 2, 1), cursorLineState(grid(20, "output", "$ echo abc defghijkl\\", "mnX"), 2, 1))
        assertNotEquals(cursorLineState(wide, 2, 1), cursorLineState(wide, 2, 0))
        // A wide character that went to the next row leaves no blank in the text.
        assertEquals("$ abW\u00005", cursorLineState(grid(5, "$ ab \\", "<>"), 1, 2))
    }
}
