package ru.adsrv.frpjump.settings

/** Termux's total main-buffer row count, including the visible screen. Captured by new terminals only. */
const val DEFAULT_TERMINAL_HISTORY_ROWS = 1000
val TERMINAL_HISTORY_CHOICES = listOf(500, 1000, 3000, 10000)

fun validTerminalHistoryRows(rows: Int): Boolean = rows in TERMINAL_HISTORY_CHOICES
