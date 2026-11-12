package ru.adsrv.frpjump.ui.ssh

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The disclosure target's place, shared with the image bar so its Cancel never sits under it. */
class DisclosureGeometryTest {
    @Test fun portraitAndLandscapeReservesFollowTheTargetsOffset() {
        // 377 dp inside the padding (a 1080 px phone at 2.75): the offset is positive, the target nearly at the edge.
        val portrait = disclosureGeometry(377.dp)
        assertEquals(48f, portrait.targetWidth.value, 0.01f)
        assertEquals(PANEL_SIDE_PADDING.value - portrait.targetOffset.value + 48f, disclosureEndReserve(377.dp).value, 0.01f)
        assertTrue(portrait.targetOffset.value > 0)
        // 787 dp (a landscape phone): the cells are wide, the offset is negative, so the target is centred over the last
        // cell and reaches much further from the end than a fixed margin would say.
        val landscape = disclosureGeometry(787.dp)
        assertTrue(landscape.targetOffset.value < 0)
        assertEquals(74.4f, disclosureEndReserve(787.dp).value, 0.1f)
        assertTrue(disclosureEndReserve(787.dp).value > disclosureEndReserve(377.dp).value)
    }

    @Test fun theReserveAlwaysCoversTheTargetsWidthLessWhatMayProtrude() {
        for (w in 150..1400 step 7) {
            val g = disclosureGeometry(w.dp)
            assertTrue("width $w", disclosureEndReserve(w.dp).value >= g.targetWidth.value - 20f)
        }
    }
}
