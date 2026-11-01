package ru.adsrv.frpjump.ui.ssh

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth
import com.termux.view.TerminalView
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.SshState
import kotlin.math.abs

/**
 * Tap the command line to move the shell's cursor there: the app sends as
 * many ← or → as [tapCursorMoves] counts, and the shell (readline) moves
 * its cursor and echoes it. Main thread.
 *
 * - Plain shell: only on the normal screen; a full-screen program (`vim`,
 *   `htop`, `less`) gets the tap as before, a mouse click included.
 * - tmux (the app's sessions; tmux is always on the alternate screen
 *   toward the app): the tap is kept from tmux as a click, then one exec
 *   asks tmux whether the pane is on the shell's normal screen
 *   ([SshSession.askTapToShell]); yes sends the arrows, no hands the
 *   program the click it would have had.
 *
 * The arrows go as plain keys: a held Ctrl / Alt is not used up.
 */
class TerminalTap(private val view: TerminalView, private val session: () -> SshSession) {
    /** Takes a tap at [event] if it lands on the cursor's line; false leaves it to the view as before. */
    fun onTap(event: MotionEvent): Boolean {
        val s = session()
        if (s.state.value != SshState.Open || view.currentSession !== s.terminal.session) return false
        val emulator = view.mEmulator ?: return false
        val tmux = s.tmux.inUse
        if (!tmux && emulator.isAlternateBufferActive) return false
        val (column, row) = view.getColumnAndRow(event, true)
        val moves = tapCursorMoves(BufferGrid(emulator), emulator.cursorRow, emulator.cursorCol, row, column)
            ?: return false
        if (!tmux) {
            sendArrows(s, moves)
            return true
        }
        s.askTapToShell { shell ->
            view.post {
                if (shell) sendArrows(s, moves) else click(s, column, row)
            }
        }
        return true
    }

    private fun sendArrows(s: SshSession, moves: Int) {
        val terminal = s.terminal.session
        val emulator = terminal.emulator ?: return
        if (moves == 0 || !terminal.isRunning) return
        val key = if (moves < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val code = KeyHandler.getCode(key, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
            ?: return
        s.typed()
        terminal.write(code.repeat(abs(moves)))
    }

    /** The click the view would have sent for the tap at [column], [row] (screen cells, from 0). */
    private fun click(s: SshSession, column: Int, row: Int) {
        val emulator = s.terminal.session.emulator ?: return
        if (!emulator.isMouseTrackingActive) return
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column + 1, row + 1, true)
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column + 1, row + 1, false)
    }
}

/** [emulator]'s screen and scrollback as [TapGrid]; read on the main thread, where the emulator takes its input. */
private class BufferGrid(emulator: TerminalEmulator) : TapGrid {
    private val screen: TerminalBuffer = emulator.screen
    override val columns = emulator.mColumns
    override val firstRow = -screen.activeTranscriptRows
    override val lastRow = emulator.mRows - 1

    /** Per row: what each column holds, worked out once. */
    private val cells = HashMap<Int, ByteArray>()

    override fun wraps(row: Int): Boolean {
        screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
        return screen.getLineWrap(row)
    }

    override fun isWideTail(row: Int, column: Int) = cellsOf(row)[column] == WIDE_TAIL

    override fun isBlank(row: Int, column: Int) = cellsOf(row)[column] == BLANK

    /** Walks the row's text by display width, as the emulator lays it out. */
    private fun cellsOf(row: Int): ByteArray = cells.getOrPut(row) {
        val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
        val kinds = ByteArray(columns) { BLANK }
        val text = line.mText
        val used = line.spaceUsed
        var index = 0
        var column = 0
        while (index < used && column < columns) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            val width = WcWidth.width(codePoint)
            if (width <= 0) continue
            if (codePoint != ' '.code) kinds[column] = TEXT
            if (width == 2 && column + 1 < columns) kinds[column + 1] = WIDE_TAIL
            column += width
        }
        kinds
    }

    private companion object {
        const val BLANK: Byte = 0
        const val TEXT: Byte = 1
        const val WIDE_TAIL: Byte = 2
    }
}
