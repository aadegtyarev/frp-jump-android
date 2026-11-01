package ru.adsrv.frpjump.mqtt.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.mqtt.SnapshotEnd
import ru.adsrv.frpjump.mqtt.SnapshotState
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WB_META_FILTERS

class PluginObserverTest {
    @Test
    fun `off drops the filters whatever the session saw`() {
        listOf(SnapshotState.Idle, SnapshotState.Reading, SnapshotState.Done(SnapshotEnd.QUIET), SnapshotState.Refused).forEach {
            assertEquals(emptyList<String>(), wbFilters(on = false, seen = true, snapshot = it))
        }
    }

    @Test
    fun `on, the filters are held once meta is seen or the snapshot may have missed it`() {
        assertEquals(WB_META_FILTERS, wbFilters(on = true, seen = true, snapshot = SnapshotState.Done(SnapshotEnd.QUIET)))
        assertEquals(WB_META_FILTERS, wbFilters(on = true, seen = false, snapshot = SnapshotState.Done(SnapshotEnd.TIMED_OUT)))
        assertEquals(WB_META_FILTERS, wbFilters(on = true, seen = false, snapshot = SnapshotState.Refused))
        // A whole tree without meta drops them.
        assertEquals(emptyList<String>(), wbFilters(on = true, seen = false, snapshot = SnapshotState.Done(SnapshotEnd.QUIET)))
        // Still reading: left as held.
        assertNull(wbFilters(on = true, seen = false, snapshot = SnapshotState.Reading))
        assertNull(wbFilters(on = true, seen = false, snapshot = SnapshotState.Idle))
    }
}
