package ru.adsrv.frpjump.ui.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.MqttFailure
import ru.adsrv.frpjump.mqtt.MqttState
import ru.adsrv.frpjump.mqtt.MqttVersion

class StateBannerTest {
    @Test
    fun waitingStatesLiveOnlyInTheSubtitle() {
        assertFalse(stateNeedsBanner(MqttState.Reconnecting, closedByTunnel = false, subtitleShown = true))
        assertFalse(stateNeedsBanner(MqttState.Connecting, closedByTunnel = false, subtitleShown = true))
        assertTrue(stateBusy(MqttState.Reconnecting))
        assertTrue(stateBusy(MqttState.Connecting))
    }

    @Test
    fun withoutTheSubtitleTheBannerSaysTheState() {
        assertTrue(stateNeedsBanner(MqttState.Reconnecting, closedByTunnel = false, subtitleShown = false))
        assertTrue(stateNeedsBanner(MqttState.Connecting, closedByTunnel = false, subtitleShown = false))
        assertTrue(stateNeedsBanner(MqttState.Ended, closedByTunnel = false, subtitleShown = false))
    }

    @Test
    fun aPlainCloseIsNotRepeatedButItsReasonIs() {
        assertFalse(stateNeedsBanner(MqttState.Ended, closedByTunnel = false, subtitleShown = true))
        assertTrue(stateNeedsBanner(MqttState.Ended, closedByTunnel = true, subtitleShown = true))
        assertFalse(stateBusy(MqttState.Ended))
    }

    @Test
    fun statesWithAnActionKeepTheirBanner() {
        assertTrue(stateNeedsBanner(MqttState.NeedsLogin(null, wrong = false), closedByTunnel = false, subtitleShown = true))
        assertTrue(stateNeedsBanner(MqttState.Failed(MqttFailure.NO_BROKER), closedByTunnel = false, subtitleShown = true))
        assertFalse(stateBusy(MqttState.Failed(MqttFailure.REFUSED)))
    }

    @Test
    fun anOpenSessionHasNoBanner() {
        assertFalse(stateNeedsBanner(MqttState.Open(MqttVersion.V5), closedByTunnel = false, subtitleShown = true))
        assertFalse(stateBusy(MqttState.Open(MqttVersion.V5)))
    }
}
