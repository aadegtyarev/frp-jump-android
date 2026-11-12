package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.errors.DevicePortNotOpen
import ru.adsrv.frpjump.errors.DomainError
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ops.TunnelFailure
import ru.adsrv.frpjump.ops.TunnelRefusal

/** The owner's words for the tunnel results are the ones the Connections actions showed before the operation moved out. */
class OpTextsTest {
    @Test fun `profile gone says the profile was forgotten`() = assertEquals(Texts.get(R.string.profile_gone), TunnelRefusal.ProfileGone.userText())

    @Test fun `a tunnel that did not come up says the managers error or that it is not connected`() {
        assertEquals("server said no", TunnelFailure.DidNotComeUp(DomainError.ServerError("server said no")).userText())
        assertEquals(Texts.get(R.string.files_tunnel_down), TunnelFailure.DidNotComeUp(null).userText())
    }

    @Test fun `a device that does not listen says the managers explanation as it is`() =
        assertEquals(Texts.get(R.string.device_port_not_open, 80), TunnelFailure.DeviceNotListening(DevicePortNotOpen(80)).userText())
}
