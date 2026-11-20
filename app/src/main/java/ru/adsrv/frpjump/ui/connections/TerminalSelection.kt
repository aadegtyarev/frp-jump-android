package ru.adsrv.frpjump.ui.connections

import ru.adsrv.frpjump.sessions.sessionHidden
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef

/** Exact visible terminal sessions on a tunnel, keeping the owner's order. */
internal fun <T> matchingTerminalSessions(
    sessions: List<T>, tunnel: TunnelRef, hidden: Set<String>, tunnelOf: (T) -> TunnelRef, idOf: (T) -> Int,
): List<T> = sessions.filter { tunnelOf(it) == tunnel && !sessionHidden(hidden, SessionKind.TERMINAL, idOf(it), tunnel) }

internal sealed interface TerminalDestination {
    data object New : TerminalDestination
    data class Existing(val id: Int) : TerminalDestination
    data object Choose : TerminalDestination
}

/** One choice is opened directly; multiple choices require the owner to choose. */
internal fun terminalDestination(ids: List<Int>): TerminalDestination = when (ids.size) {
    0 -> TerminalDestination.New
    1 -> TerminalDestination.Existing(ids.single())
    else -> TerminalDestination.Choose
}
