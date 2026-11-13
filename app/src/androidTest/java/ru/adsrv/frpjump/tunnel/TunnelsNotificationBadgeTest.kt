package ru.adsrv.frpjump.tunnel

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.MainActivity

/** The tunnels notification carries the launcher badge number, and its channel lets the launcher show it. */
@RunWith(AndroidJUnit4::class)
class TunnelsNotificationBadgeTest {
    @Before
    fun allowNotifications() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun pending() = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun built(summary: TunnelSummary, text: String? = "text") =
        tunnelsNotification(context, summary, "title", text, pending(), pending(), "Disconnect all")

    @Test
    fun builtNotificationNumberIsTheTunnelCountAndNothingElseChanged() {
        for (count in listOf(0, 1, 5)) {
            assertEquals(count, built(tunnelSummary(mapOf("p1" to count))).number)
        }
        val notification = built(tunnelSummary(mapOf("p1" to 2, "p2" to 3)))
        assertEquals(5, notification.number)
        assertEquals("tunnels", notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(1, notification.actions.size)
        assertEquals("Disconnect all", notification.actions[0].title.toString())
        assertEquals("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("text", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNotNull(notification.contentIntent)
    }

    /** The system keeps the number it is given (the launcher reads it from here). */
    @Test
    fun postedNotificationKeepsItsNumber() {
        val manager = context.getSystemService(NotificationManager::class.java)
        tunnelsChannel(context, context.resources)
        val id = 9001
        try {
            manager.notify(id, built(tunnelSummary(mapOf("p1" to 4))))
            val posted = manager.activeNotifications.firstOrNull { it.id == id }
            assertNotNull("not posted (notifications blocked?)", posted)
            assertEquals(4, posted!!.notification.number)
        } finally {
            manager.cancel(id)
        }
    }

    @Test
    fun channelDefinitionShowsBadgesAtLowImportance() {
        val channel = newTunnelsChannel(context.resources)
        assertEquals("tunnels", channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue(channel.canShowBadge())
    }
}
