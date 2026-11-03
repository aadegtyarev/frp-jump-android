package ru.adsrv.frpjump.ui.mqtt

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.mqtt.*
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Actual warning component driven by an actual session on ExecutorScheduler; no sockets. */
@RunWith(AndroidJUnit4::class)
class MqttLoginStorageBannerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<Fixture>()

    @Before fun configureDisplay() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val end = SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After fun cleanUp() { fixtures.forEach { it.session.close(); it.drain() } }

    @Test fun rememberWarningRetryOnlyWritesOnThePhone() = checkRetry(forget = false)
    @Test fun forgetWarningDoesNotClaimOldCredentialsWereRemoved() = checkRetry(forget = true)

    private fun checkRetry(forget: Boolean) {
        val f = show(forget)
        val resource = if (forget) R.string.mqtt_login_not_removed else R.string.mqtt_login_not_saved
        compose.onNodeWithText(context.getString(resource)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).assertIsDisplayed()
        assertEquals(MqttState.Open(MqttVersion.V5), f.session.state.value)
        if (forget) assertEquals("old", f.logins.get("p", "c")?.user)
        f.fail.set(false)
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.waitUntil(5_000) { f.session.loginStorageFailure.value == null }
        compose.onNodeWithText(context.getString(resource)).assertDoesNotExist()
        assertEquals("Local retry must not authenticate again", 2, f.requests.get())
        assertEquals("Local retry must not resubscribe", 1, f.subscribes.get())
        assertEquals(0, f.closed.get())
        if (forget) assertNull(f.logins.get("p", "c")) else assertEquals("typed", f.logins.get("p", "c")?.user)
    }

    @Test fun reconnectingHidesStorageActionAndRejectsAnOldClick() {
        val f = show(false)
        val issue = checkNotNull(f.session.loginStorageFailure.value)
        val writes = f.writes.get()
        f.reconnectAllowed.set(false)
        f.events!!.lost()
        f.drain()
        assertEquals(MqttState.Reconnecting, f.session.state.value)
        compose.onNodeWithText(context.getString(R.string.mqtt_login_not_saved)).assertDoesNotExist()
        f.fail.set(false)
        f.session.retryLoginStorage(issue)
        f.drain()
        assertEquals(writes, f.writes.get())
    }

    private fun show(forget: Boolean): Fixture {
        val f = Fixture(forget).also { fixtures += it }
        compose.setContent {
            FrpJumpTheme {
                val state by f.session.state.collectAsState()
                val issue by f.session.loginStorageFailure.collectAsState()
                Column { MqttLoginStorageBanner(state, issue, f.session::retryLoginStorage) }
            }
        }
        return f
    }

    private class Fixture(forget: Boolean) {
        val fail = AtomicBoolean(false)
        val requests = AtomicInteger()
        val subscribes = AtomicInteger()
        val writes = AtomicInteger()
        val closed = AtomicInteger()
        val reconnectAllowed = AtomicBoolean(true)
        @Volatile var events: LinkEvents? = null
        private val storage = Memory { writes.incrementAndGet(); if (forget && fail.get()) throw IOException("Synthetic write failure") }
        private val box = object : SecretBox {
            override fun seal(plain: String): String {
                if (!forget && fail.get()) throw IllegalStateException("Synthetic locked key")
                return "synthetic:" + plain.reversed()
            }
            override fun open(sealed: String) = sealed.removePrefix("synthetic:").reversed()
            override fun destroy() = Unit
        }
        val logins = MqttLogins(storage, box)
        private val scheduler = ExecutorScheduler()
        private val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) { subscribes.incrementAndGet(); done(true) }
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) = Unit
            override fun close() { closed.incrementAndGet() }
        }
        private val connector = object : MqttConnector {
            override fun connect(request: ConnectRequest, callback: LinkEvents, done: (ConnectOutcome) -> Unit) {
                events = callback
                val n = requests.incrementAndGet()
                done(when {
                    n == 1 -> ConnectOutcome.Refused(ConnectRefusal.BAD_LOGIN)
                    !reconnectAllowed.get() -> ConnectOutcome.Refused(ConnectRefusal.NO_BROKER)
                    else -> ConnectOutcome.Opened(link)
                })
            }
        }
        val session = MqttSession(1, "p", "c", "synthetic", 1883, 12345,
            MqttEnvironment(logins, PinStore(Memory()), connector, scheduler))
        init {
            if (forget) logins.remember("p", "c", MqttLogin("old", "synthetic-old"))
            session.connect()
            drain()
            fail.set(true)
            session.login("typed", "synthetic-typed", remember = !forget)
            drain()
        }
        fun drain() { repeat(4) { scheduler.runAfterPending {} } }
    }

    private class Memory(private val beforeWrite: () -> Unit = {}) : TextStorage {
        @Volatile private var value: String? = null
        override fun read() = value
        override fun write(text: String) { beforeWrite(); value = text }
        override fun delete() { value = null }
    }
}
