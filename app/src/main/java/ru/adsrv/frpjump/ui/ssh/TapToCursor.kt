package ru.adsrv.frpjump.ui.ssh

/**
 * The terminal's screen as tap-to-cursor reads it. Rows are numbered as the
 * emulator does: 0 is the top of the screen, negative rows are scrollback.
 */
interface TapGrid {
    val columns: Int

    /** The topmost row there is (the oldest scrollback row). */
    val firstRow: Int

    /** The bottom row of the screen. */
    val lastRow: Int

    /** Row [row] runs on into the next one: the emulator wrapped it at the right margin. */
    fun wraps(row: Int): Boolean

    /** Column [column] of [row] is the right half of a wide character (CJK, most emoji). */
    fun isWideTail(row: Int, column: Int): Boolean

    /** Column [column] of [row] holds nothing but a space. */
    fun isBlank(row: Int, column: Int): Boolean

    /** The text in column [column] of [row] (a space when blank, empty for a wide character's right half). */
    fun text(row: Int, column: Int): String
}

/** The most arrow keys one tap sends. */
const val MAX_TAP_MOVES = 2000

/**
 * How many characters the shell's cursor has to move for a tap on
 * ([tapRow], [tapColumn]): positive is right (→), negative is left (←),
 * at most [MAX_TAP_MOVES] either way. Null when the tap is not on the
 * cursor's line: the row of the cursor and the rows wrapped into it or
 * from it, as one logical line. A row the shell broke with a real line
 * break is another line, and so is output above and the empty screen below.
 *
 * Characters are counted, not columns: a wide character is one move. A tap
 * on its right half lands on it. A tap past the end of the text lands at
 * the end (or stays, when the cursor is already past it: typed trailing
 * spaces). A blank at the end of a wrapped row, left by a wide character
 * that did not fit there, is not a character.
 */
fun tapCursorMoves(grid: TapGrid, cursorRow: Int, cursorColumn: Int, tapRow: Int, tapColumn: Int): Int? {
    if (grid.columns <= 0 || tapRow !in grid.firstRow..grid.lastRow || cursorRow !in grid.firstRow..grid.lastRow) return null
    var start = cursorRow
    while (start > grid.firstRow && grid.wraps(start - 1)) start--
    var end = cursorRow
    while (end < grid.lastRow && grid.wraps(end)) end++
    if (tapRow !in start..end) return null

    val line = LogicalLine(grid, start, end)
    val cursor = line.indexOf(cursorRow, cursorColumn.coerceIn(0, grid.columns - 1))
    val tap = line.indexOf(tapRow, tapColumn.coerceIn(0, grid.columns - 1))
        .coerceAtMost(maxOf(line.textEnd(), cursor))
    return (tap - cursor).coerceIn(-MAX_TAP_MOVES, MAX_TAP_MOVES)
}

/**
 * The cursor's logical line as the shell has it: its text, without its
 * trailing blanks, and the cursor's place in it in characters, whatever
 * the width it wraps at. The same for a screen that only re-wrapped
 * (the keyboard showed and tmux redrew); different once the line, the
 * cursor or the screen (another program) changed. Empty when the cursor
 * is off the grid.
 */
fun cursorLineState(grid: TapGrid, cursorRow: Int, cursorColumn: Int): String {
    if (grid.columns <= 0 || cursorRow !in grid.firstRow..grid.lastRow) return ""
    var start = cursorRow
    while (start > grid.firstRow && grid.wraps(start - 1)) start--
    var end = cursorRow
    while (end < grid.lastRow && grid.wraps(end)) end++
    val line = LogicalLine(grid, start, end)
    val text = StringBuilder()
    for (row in start..end) {
        for (column in 0 until grid.columns) {
            if (!line.isWrapGap(row, column)) text.append(grid.text(row, column))
        }
    }
    return text.trimEnd().toString() + "\u0000" + line.indexOf(cursorRow, cursorColumn.coerceIn(0, grid.columns - 1))
}

/** Rows [start]..[end] of [grid], wrapped into one line; positions in it count characters. */
private class LogicalLine(private val grid: TapGrid, private val start: Int, private val end: Int) {
    /** How many characters come before the one at ([row], [column]); the right half of a wide one counts as it. */
    fun indexOf(row: Int, column: Int): Int {
        var count = 0
        for (r in start until row) count += charactersIn(r, grid.columns)
        var c = column
        while (c > 0 && grid.isWideTail(row, c)) c--
        return count + charactersIn(row, c)
    }

    /** Just past the last character that is not a space: where the text ends. */
    fun textEnd(): Int {
        for (row in end downTo start) {
            for (column in grid.columns - 1 downTo 0) {
                if (!grid.isBlank(row, column) && !grid.isWideTail(row, column)) return indexOf(row, column) + 1
            }
        }
        return 0
    }

    /** Characters in the first [columns] columns of [row]. */
    private fun charactersIn(row: Int, columns: Int): Int {
        var count = 0
        for (column in 0 until columns) {
            if (!grid.isWideTail(row, column) && !isWrapGap(row, column)) count++
        }
        return count
    }

    /** The last column of a wrapped row left blank because the wide character after it went to the next row. */
    fun isWrapGap(row: Int, column: Int): Boolean =
        column == grid.columns - 1 && row < end && grid.wraps(row) && grid.isBlank(row, column) &&
            grid.columns > 1 && grid.isWideTail(row + 1, 1)
}
