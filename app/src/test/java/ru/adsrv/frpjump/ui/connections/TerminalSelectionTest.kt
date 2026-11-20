package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.tunnel.SessionKind
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.undo.PendingUndo

class TerminalSelectionTest {
    private data class Choice(val id: Int, val tunnel: TunnelRef)
    @Test fun `route uses exact identity and excludes pending sessions and tunnels`() {
        val target = TunnelRef("work", "router", 22)
        val choices = listOf(Choice(1, target), Choice(2, target), Choice(3, TunnelRef("home", "router", 22)),
            Choice(4, TunnelRef("work", "other", 22)), Choice(5, TunnelRef("work", "router", 2222)))
        fun select(hidden: Set<String>, list: List<Choice> = choices) = matchingTerminalSessions(list, target, hidden, Choice::tunnel, Choice::id).map { it.id }
        assertEquals(TerminalDestination.Choose, terminalDestination(select(emptySet())))
        assertEquals(TerminalDestination.Existing(2), terminalDestination(select(setOf(PendingUndo.sessionKey(SessionKind.TERMINAL, 1)))))
        assertEquals(TerminalDestination.New, terminalDestination(select(setOf(PendingUndo.tunnelKey("work", "router", 22)))))
        assertEquals(TerminalDestination.New, terminalDestination(select(emptySet(), choices.drop(2))))
        assertEquals(listOf(1, 2), select(setOf(PendingUndo.sessionKey(SessionKind.MQTT, 1))))
    }
}
