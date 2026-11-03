package ru.adsrv.frpjump.ui.mqtt

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.*
import ru.adsrv.frpjump.settings.MqttCacheLimits
import ru.adsrv.frpjump.settings.MqttOverflow
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Actual cache/session scheduler and production feedback/clear/retry components, fake broker only. */
@RunWith(AndroidJUnit4::class)
class MqttCacheFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scheduler = ExecutorScheduler()
    private var session: MqttSession? = null
    private val events = AtomicReference<LinkEvents>()
    private class Memory : TextStorage {
        var value: String? = null
        override fun read() = value
        override fun write(text: String) { value = text }
        override fun delete() { value = null }
    }
    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }
    @After fun cleanup() { session?.close(); drain() }
    private fun drain() { repeat(4) { scheduler.runAfterPending {} } }
    private fun start(policy: MqttOverflow): MqttSession {
        val box = object : SecretBox {
            override fun seal(plain: String): String = error("No credentials in cache feedback test")
            override fun open(sealed: String): String = error("No credentials in cache feedback test")
            override fun destroy() = Unit
        }
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) { done(true) }
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) { done(PublishAnswer.Accepted) }
            override fun close() = Unit
        }
        val connector = MqttConnector { _, callbacks, done -> events.set(callbacks); done(ConnectOutcome.Opened(link)) }
        val s = MqttSession(1, "synthetic", "cache", "synthetic", 1883, 9,
            MqttEnvironment(MqttLogins(Memory(), box), PinStore(Memory()), connector, scheduler,
                cacheLimits = { MqttCacheLimits(storageBytes = 4096, overflow = policy) }))
        session = s
        s.connect(); drain()
        compose.setContent {
            FrpJumpTheme {
                val state by s.state.collectAsState()
                val dropped by s.cacheDropped.collectAsState()
                val snapshot by s.snapshot.collectAsState()
                Column {
                    MqttCacheBanner(state, dropped, s.tree.storedBytes, s.tree.storageLimitBytes, onRefresh = { s.refreshTree() })
                    Banners(MqttUiState(state = state, snapshot = snapshot), "synthetic", 1883, subtitleShown = false,
                        onRetry = { s.retry() }, onLogin = {}, onPinBranch = {})
                }
            }
        }
        return s
    }
    private fun overflow() { repeat(4) { events.get().message("x", ByteArray(1024), false, 0) }; drain() }

    @Test fun cacheDropShowsMissingDataAndUsageWithoutFalselyCompletingTheSnapshot() {
        val s = start(MqttOverflow.DROP_INCOMING)
        overflow()
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_cache_missing, 1L)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_cache_usage, s.tree.storedBytes, 4096L)).assertExists()
        assertEquals(SnapshotState.Done(SnapshotEnd.STORAGE_LIMIT), s.snapshot.value)
        assertEquals(MqttState.Open(MqttVersion.V5), s.state.value)
        events.get().message("x", byteArrayOf(1), false, 0); drain()
        assertEquals(4, s.tree.history(MqttPath.parse("x")).size)
        assertEquals(1L, s.cacheDropped.value)
    }

    @Test fun stoppedCacheCanActuallyBeClearedBeforeManualRetryWithoutEnablingEndedRefresh() {
        val s = start(MqttOverflow.STOP_SESSION)
        overflow()
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_cache_stopped)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.mqtt_refresh_tree)).assertIsEnabled().performClick()
        drain()
        assertEquals(TopicTree.NODE_CHARGE, s.tree.storedBytes)
        assertEquals(0, s.tree.topicCount)
        assertEquals(MqttState.Failed(MqttFailure.CACHE_LIMIT), s.state.value)
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
        drain()
        events.get().message("after-clear", byteArrayOf(1), false, 0); drain()
        assertEquals(MqttState.Open(MqttVersion.V5), s.state.value)
        assertEquals(1, s.tree.topicCount)
        assertEquals(1L, s.cacheDropped.value)
        assertFalse(cacheCanRefresh(MqttState.Ended))
        assertFalse(cacheCanRefresh(MqttState.Failed(MqttFailure.NO_BROKER)))
        assertFalse(cacheCanRefresh(MqttState.Failed(MqttFailure.LOCAL_LIMIT)))
    }
}
