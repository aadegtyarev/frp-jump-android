package com.termux.terminal

/** Rows of history [emulator]'s current buffer can keep (the owner's «История терминала» for the main screen; none on a full-screen program's own screen). */
fun transcriptCapacity(emulator: TerminalEmulator): Int = emulator.screen.mTotalRows - emulator.screen.mScreenRows
