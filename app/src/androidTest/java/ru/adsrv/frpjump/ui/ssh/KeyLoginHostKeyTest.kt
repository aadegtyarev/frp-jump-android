package ru.adsrv.frpjump.ui.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.ssh.HostKeyCheck
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.KeyInstallResult
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.KeyInstalling
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshLoginMemory
import ru.adsrv.frpjump.store.TextStorage

/** The host-key answer of "Use key login" when the save fails: a fake installer, in-memory stores, no network. */
@RunWith(AndroidJUnit4::class)
class KeyLoginHostKeyTest {
    private class Disk(var text: String? = null, @Volatile var fail: Boolean = false) : TextStorage {
        override fun read() = text
        override fun write(text: String) {
            if (fail) throw IOException("disk full")
            this.text = text
        }
        override fun delete() { text = null }
    }

    private val old = PinnedHostKey("synthetic-type", "synthetic-old")
    private val presented = PinnedHostKey("synthetic-type", "synthetic-presented")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val installs = AtomicInteger()

    /** The first run meets the host key (as [check] says); later ones succeed. */
    private fun installer(check: HostKeyCheck) = object : KeyInstalling {
        override suspend fun install(target: KeyInstallTarget, typed: String?): KeyInstallResult =
            if (installs.getAndIncrement() == 0) KeyInstallResult.HostKey(check, presented) else KeyInstallResult.Installed
        override fun cancel() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun await(what: String, done: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!main(done)) delay(10) }
    }

    @Test
    fun failedTrustSaveShowsTheErrorTrustsNothingAndCanBeRetried() = failed(HostKeyCheck.Unknown, forget = false)

    @Test
    fun failedForgetSaveShowsTheErrorAndKeepsTheOldPin() = failed(HostKeyCheck.Changed(old), forget = true)

    private fun failed(check: HostKeyCheck, forget: Boolean) {
        val disk = Disk()
        val keys = HostKeyStore(disk)
        if (forget) keys.pin("dev", 22, old)
        val target = KeyInstallTarget("synthetic-profile", keys, "dev", 22, "root", SshLoginMemory(Disk()), { 1 })
        val flow = main { KeyLoginFlow(installer(check), scope) }
        main { flow.start(target) }
        await("host key asked") { flow.step is KeyLoginStep.HostKey }
        disk.fail = true
        main { flow.hostKeyAnswered() }
        await("error shown") { flow.step is KeyLoginStep.Done }
        val done = main { flow.step as KeyLoginStep.Done }
        val result = done.result as KeyInstallResult.HostKeySaveFailed
        assertTrue(result.detail, result.detail.contains("disk full"))
        assertEquals(forget, result.forgot)
        assertEquals(1, installs.get())
        assertEquals(if (forget) old else null, keys.pinned("dev", 22))
        // Retry once the disk works: from the start, the host key is asked again, then it goes through.
        disk.fail = false
        installs.set(0)
        main { flow.retry() }
        await("host key asked again") { flow.step is KeyLoginStep.HostKey }
        main { flow.hostKeyAnswered() }
        await("done") { (flow.step as? KeyLoginStep.Done)?.result == KeyInstallResult.Installed }
        assertEquals(if (forget) null else presented, keys.pinned("dev", 22))
    }
}
