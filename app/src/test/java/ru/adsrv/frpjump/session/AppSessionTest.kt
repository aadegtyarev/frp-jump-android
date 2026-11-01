package ru.adsrv.frpjump.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSessionTest {
    @Test
    fun `a notice waits for the device list of the profile shown next`() {
        val session = AppSession()
        session.postNotice("forgotten", forProfile = "next")
        // The list of the profile being left does not take it while the app moves on.
        assertNull(session.takeNotice("gone"))
        assertEquals("forgotten", session.takeNotice("next"))
        assertNull(session.takeNotice("next"))
    }

    @Test
    fun `with no profile left, the enrollment screen takes it`() {
        val session = AppSession()
        session.postNotice("server not told", forProfile = null)
        assertNull(session.takeNotice("last"))
        assertEquals("server not told", session.takeAnyNotice())
        assertNull(session.takeAnyNotice())
    }
}
