package ru.adsrv.frpjump.ui

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.MainActivity
import ru.adsrv.frpjump.agent.AgentModule
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.assistant.AssistantChatOwner
import ru.adsrv.frpjump.assistant.AssistantProviderDraft
import ru.adsrv.frpjump.assistant.AssistantProviderSettings
import ru.adsrv.frpjump.assistant.AssistantProviderStore
import ru.adsrv.frpjump.assistant.AssistantPrompts
import ru.adsrv.frpjump.assistant.AssistantStopReceiver
import ru.adsrv.frpjump.assistant.ChatActivity
import ru.adsrv.frpjump.assistant.ChatEnd
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

/**
 * Р32 part B on the real app: the notification's Stop (a not exported receiver) stops a running fake attempt and is harmless with none;
 * a foreign intent to MainActivity (the removed OPEN_DEVICE action and extras, and the trampoline's would-be extras) leads nowhere
 * (Р41, Р47). Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
class AssistantNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<FrpJumpApp>()
    private var original: AppSettings? = null
    private var owner: AssistantChatOwner? = null
    private var scope: CoroutineScope? = null

    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }

    @Before fun remember() { original = app.settings.settings.value }

    @After fun restore() {
        app.chatOwnerOverride = null
        owner?.shutdown(); scope?.cancel()
        original?.let { old -> app.settings.update { old } }
        app.assistantActivity.value = ChatActivity.IDLE
        app.assistantEnd.value = ChatEnd.NONE
        app.openAssistantRequest.value = false
    }

    private fun waitFor(what: String, ms: Long = 10_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ms
        while (!check() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(what, check())
    }

    private fun stopBroadcast() = app.sendBroadcast(Intent(app, AssistantStopReceiver::class.java))

    @Test fun theStopReceiverStopsTheRunningAttemptAndDoesNothingWithNone() {
        val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
        val store = AssistantProviderStore(Memory(), object : SecretBox {
            override fun seal(plain: String) = "synthetic-sealed"
            override fun open(sealed: String): String = error("no credentials")
            override fun destroy() = Unit
        }, settings.assistantChat).apply { save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key")) }
        val held = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val answered = java.util.concurrent.atomic.AtomicBoolean()
        val chatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
        val owner = AssistantChatOwner(chatScope, settings, store, module, null, null, null, null,
            lazy { AssistantPrompts("system", "summary") }, prepare = {
                AgentProvider { entered.complete(Unit); held.await(); answered.set(true); AgentReply("never") }
            }, realtime = SystemClock::elapsedRealtime, activityOut = app.assistantActivity, endOut = app.assistantEnd).also { this.owner = it }
        app.chatOwnerOverride = owner

        stopBroadcast()
        SystemClock.sleep(500)
        assertEquals("no attempt: nothing happens", ChatActivity.IDLE, app.assistantActivity.value)

        val chat = app.runOnMain { owner.attach() }
        waitFor("history ready") { !chat.state.value.loading }
        chat.edit("synthetic question"); chat.send()
        waitFor("running") { app.assistantActivity.value == ChatActivity.WORKING && entered.isCompleted }
        stopBroadcast()
        waitFor("stopped by the receiver") { app.assistantActivity.value == ChatActivity.IDLE }
        stopBroadcast()
        held.complete(Unit)
        SystemClock.sleep(300)
        assertFalse("the provider's reply was never used", answered.get() && chat.state.value.turns.any { it.answer == "never" })
        assertEquals("the owner's Stop reports no end", ChatEnd.NONE, app.assistantEnd.value)
    }

    private fun <T> FrpJumpApp.runOnMain(block: () -> T): T {
        var result: Result<T>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { result = runCatching(block); latch.countDown() }
        check(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    private fun foreign(): Intent = Intent(app, MainActivity::class.java)
        .setAction("ru.adsrv.frpjump.OPEN_DEVICE")
        .putExtra("profile", "synthetic-profile").putExtra("device", "synthetic-device")
        .putExtra("open_chat", true).putExtra("route", "assistant/chat").putExtra("stop", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test fun aForeignIntentToMainActivityLeadsNowhereAndStopsNothing() {
        app.assistantActivity.value = ChatActivity.WORKING
        val before = app.profiles.state.value.index.current
        ActivityScenario.launch<MainActivity>(foreign()).use { scenario ->
            waitFor("app visible") { app.visible.value }
            SystemClock.sleep(1_500)
            // Delivered again to the running activity too.
            app.startActivity(foreign().addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            SystemClock.sleep(1_000)
            assertFalse("no chat opened", app.assistantChatResumed.value)
            assertFalse("no in-process request set", app.openAssistantRequest.value)
            assertEquals("the profile did not change", before, app.profiles.state.value.index.current)
            assertEquals("nothing stopped", ChatActivity.WORKING, app.assistantActivity.value)
        }
    }
}
