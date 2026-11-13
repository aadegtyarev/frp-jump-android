package ru.adsrv.frpjump.tunnel

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.MainActivity

/** The tunnels notification carries the launcher badge number, and its channel lets the launcher show it. */
@RunWith(AndroidJUnit4::class)
class TunnelsNotificationBadgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun pending() = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun built(summary: TunnelSummary) =
        tunnelsNotification(context, summary, "title", null, pending(), pending(), "Disconnect all")

    @Test
    fun builtNotificationNumberIsTheTunnelCount() {
        for (count in listOf(0, 1, 5)) {
            assertEquals(count, built(tunnelSummary(mapOf("p1" to count))).number)
        }
        assertEquals(5, built(tunnelSummary(mapOf("p1" to 2, "p2" to 3))).number)
    }

    @Test
    fun channelShowsBadges() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Android keeps an existing (even a deleted and recreated) channel's badge setting, so this checks the
        // channel as the app leaves it; it cannot prove the code's own setShowBadge on a used emulator.
        tunnelsChannel(context, context.resources)
        val channel = manager.getNotificationChannel(TunnelService.CHANNEL)
        assertNotNull(channel)
        assertTrue(channel!!.canShowBadge())
    }
}
