package ru.adsrv.frpjump.ui.enroll

import androidx.lifecycle.ViewModelStore
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.profileRoute
import ru.adsrv.frpjump.ui.profiles.ProfilesViewModel
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.api.PROTOCOL_VERSION
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.enroll.EnrollLineParser
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.profiles.*
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

/** Real ViewModel + core and HTTP parser; interceptor answers every request without a socket. */
@RunWith(AndroidJUnit4::class)
class SavedConnectionOutcomeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as FrpJumpApp
    private val fixtures = mutableListOf<Fixture>()

    @Before
    fun configureDisplay() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After
    fun cleanUp() {
        fixtures.forEach { it.release?.countDown() }
        main { fixtures.forEach { it.viewModels.clear() } }
        fixtures.forEach { it.close() }
        app.session.takeAnyNotice()
    }

    @Test
    fun savedActivationFailureDoesNotTellTheUserToDeleteTheServerEnrollment() {
        val f = fixture()
        submit(f)
        val text = f.vm.state.value.let { it.error.orEmpty() + it.notice.orEmpty() }
        assertTrue(text.isNotBlank())
        assertFalse("Saved keys must not be reported as lost with server-delete advice", text.contains("devices delete"))
        assertNotNull(f.profiles.state.value.index.find("native-2"))
        assertEquals(1, f.posts.get())
    }

    @Test
    fun retryUsesSavedIdentityWithoutAnotherEnrollmentRequest() {
        val f = fixture()
        submit(f)
        f.fail.set(false)
        submit(f)
        assertEquals("Retry must not spend another enrollment token", 1, f.posts.get())
        assertEquals("native-2", f.profiles.current?.id)
        assertEquals(2, f.profiles.state.value.index.profiles.size)
    }

    @Test
    fun leavingWhileActivationFailsPostsTruthfulSavedOutcome() {
        val f = fixture(blockFactory = true)
        main { f.vm.submit {} }
        assertTrue(f.entered.await(3, TimeUnit.SECONDS))
        main { f.viewModels.clear() }
        f.release!!.countDown()
        val notice = runBlocking { withTimeout(5_000) { app.session.notice.first { it != null }!! } }
        assertFalse("Leaving cannot convert saved keys into a NotStored notice", notice.text.contains("devices delete"))
        assertNotNull(f.profiles.state.value.index.find("native-2"))
        assertEquals(1, f.posts.get())
    }

    @Test
    fun realFormOffersRetryWithoutQrAndDoesNotSubmitAnotherToken() {
        val f = fixture()
        val done = AtomicInteger()
        compose.setContent { FrpJumpTheme { EnrollScreen(f.vm, { done.incrementAndGet() }, onBack = null) } }
        compose.onNodeWithText(app.getString(R.string.enroll_submit)).performClick()
        compose.waitUntil(5_000) { f.vm.state.value.pendingActivation != null && !f.vm.state.value.busy }
        compose.onNodeWithText(app.getString(R.string.connection_retry_start)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.enroll_step_invite)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.enroll_line_label)).assertDoesNotExist()
        f.fail.set(false)
        compose.onNodeWithText(app.getString(R.string.connection_retry_start)).performClick()
        compose.waitUntil(5_000) { done.get() == 1 }
        assertEquals(1, f.posts.get())
        assertEquals("native-2", f.profiles.current?.id)
        main { f.vm.submit { done.incrementAndGet() } }
        assertEquals("A completed form cannot replay an old click", 1, done.get())
        assertEquals(1, f.posts.get())
    }

    @Test
    fun firstSavedConnectionRoutesToManualStartInsteadOfAnotherEnrollment() {
        val f = fixture(working = false)
        submit(f)
        assertEquals("storage", runBlocking { profileRoute(f.profiles) })
        assertNull(f.profiles.current)
        f.fail.set(false)
        submit(f)
        assertEquals("devices", runBlocking { profileRoute(f.profiles) })
        assertEquals("native-1", f.profiles.current?.id)
        assertEquals(1, f.posts.get())
    }

    @Test
    fun pendingListReadsSavedServerAndReportsFailedRetryWithoutSwitching() {
        val f = fixture()
        submit(f)
        val vm = main { ProfilesViewModel(app, f.profiles).also { f.viewModels.put("profiles", it) } }
        val item = runBlocking { withTimeout(5_000) { vm.items.first { list -> list.any { it.id == "native-2" && it.serverLoaded } } } }
            .single { it.id == "native-2" }
        assertTrue(item.activationPending)
        assertEquals("synthetic.invalid", item.server?.host)
        val switched = CountDownLatch(1)
        main { vm.switchTo("native-2") { switched.countDown() } }
        val notice = runBlocking { withTimeout(5_000) { app.session.notice.first { it != null }!! } }
        assertFalse(notice.text.contains("devices delete"))
        assertEquals(1L, switched.count)
        assertEquals("native-1", f.profiles.current?.id)
        app.session.takeAnyNotice()
        f.fail.set(false)
        main { vm.switchTo("native-2") { switched.countDown() } }
        assertTrue(switched.await(5, TimeUnit.SECONDS))
        assertEquals("native-2", f.profiles.current?.id)
        assertEquals(1, f.posts.get())
    }

    @Test
    fun deletedSavedIdentityCannotBeRetriedOrReenrolledByTheOldForm() {
        val f = fixture()
        submit(f)
        runBlocking { f.profiles.forget("native-2", keepData = false) }
        f.fail.set(false)
        submit(f)
        assertNull(f.vm.state.value.pendingActivation)
        assertNotNull(f.vm.state.value.error)
        assertEquals("native-1", f.profiles.current?.id)
        assertEquals(1, f.posts.get())
    }

    @Test
    fun replacedEnrollmentCannotBeSelectedByAnOlderRetryCallback() {
        val f = fixture()
        submit(f)
        f.fail.set(false)
        runBlocking {
            f.profiles.forget("native-2", keepData = true)
            f.profiles.setCurrent("native-2")
            f.profiles.enroll(Credentials("https://replacement.invalid", "replacement", "replacement", "synthetic", "cert", "key", "ca", "replacement.invalid", 7000))
            f.profiles.setCurrent("native-1")
        }
        submit(f)
        assertEquals("native-1", f.profiles.current?.id)
        assertNull(f.vm.state.value.pendingActivation)
        assertTrue("Spent invitation must not become a fresh submission", f.vm.state.value.text.isEmpty())
        submit(f)
        assertEquals(1, f.posts.get())
    }

    @Test
    fun idleClearedFormCannotStartSavedConnectionFromALateClick() {
        val f = fixture()
        submit(f)
        main { f.viewModels.clear() }
        f.fail.set(false)
        main { f.vm.submit {} }
        instrumentation.waitForIdleSync()
        assertNull(f.profiles.runtime("native-2"))
        assertEquals("native-1", f.profiles.current?.id)
        assertEquals(1, f.posts.get())
    }

    private fun submit(f: Fixture) {
        main { f.vm.submit {} }
        runBlocking { withTimeout(5_000) { f.vm.state.first { !it.busy } } }
    }

    private fun fixture(blockFactory: Boolean = false, working: Boolean = true): Fixture {
        app.session.takeAnyNotice()
        return Fixture(blockFactory, working).also { fixtures += it }
    }

    private inner class Fixture(block: Boolean, working: Boolean) {
        val dir = File(app.cacheDir, "saved-connection-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val files = ConcurrentHashMap<String, Memory>()
        private fun storage(file: File): TextStorage = files.getOrPut(file.path) { Memory() }
        val posts = AtomicInteger()
        val fail = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release: CountDownLatch? = if (block) CountDownLatch(1) else null
        val api = FrpJumpApi(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = when {
                request.url.encodedPath.endsWith("/version") ->
                    """{"protocol_version":$PROTOCOL_VERSION,"package_version":"synthetic","features":[]}"""
                request.url.encodedPath.endsWith("/enroll") -> {
                    posts.incrementAndGet()
                    """{"protocol_version":$PROTOCOL_VERSION,"device_id":"synthetic-id","device_name":"synthetic-phone","api_token":"synthetic-token","cert_pem":"synthetic-cert","key_pem":"synthetic-key","ca_cert_pem":"synthetic-ca","server_addr":"synthetic.invalid","server_port":7000}"""
                }
                else -> error("Unexpected request in socket-free fixture")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(json.toResponseBody()).build()
        }.build())
        val record = RestoreRecord(Memory()).apply { load() }
        val shared = ProfileShared(
            api, LocalPortStore(storage(File(dir, "local_ports.json"))), MutableStateFlow(AppSettings()),
            MutableStateFlow(false), File(dir, "no-frpc"), File(dir, "frpc"),
            { error("No service in saved connection test") }, scope, ::storage,
            DirectKeptStore(Memory()), MutableStateFlow(null), record,
        )
        private var ids = 0
        val store = ProfileStore(dir, { alias -> object : SecretBox {
            override fun seal(plain: String) = "$alias|$plain"
            override fun open(sealed: String): String = sealed.removePrefix("$alias|")
            override fun destroy() = Unit
        } }, ::storage, newId = { "native-${++ids}" })
        private val made = mutableListOf<ProfileRuntime>()
        val profiles = Profiles(store, record.profiles) { p, s, current ->
            if (!working || p.id != "native-1") {
                entered.countDown()
                release?.let { check(it.await(5, TimeUnit.SECONDS)) }
                if (fail.get()) throw IOException("Synthetic activation failure")
            }
            ProfileRuntime(p.id, s.credentials(p), s.dir(p.id), current, shared).also { made += it }
        }
        val viewModels = ViewModelStore()
        val vm: EnrollViewModel
        init {
            runBlocking {
                check(profiles.load())
                if (working) profiles.enroll(Credentials("https://working.invalid", "working-id", "working-phone", "synthetic", "cert", "key", "ca", "working.invalid", 7000))
            }
            vm = main {
                EnrollViewModel(app, false, profiles, api).also {
                    viewModels.put("saved-test", it)
                    it.onText("https://synthetic.invalid " + "a".repeat(EnrollLineParser.TOKEN_LENGTH))
                }
            }
        }
        fun close() {
            made.forEach { it.close() }
            scope.cancel()
            runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]!!.join() } }
            dir.deleteRecursively()
        }
    }

    private class Memory : TextStorage {
        @Volatile private var value: String? = null
        override fun read() = value
        override fun write(text: String) { value = text }
        override fun delete() { value = null }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
