package ru.adsrv.frpjump.ui.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.HostKeyCheck
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.KeyInstallResult
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.KeyInstalling
import ru.adsrv.frpjump.ssh.LoginRefusal
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.ssh.PinnedHostKey
import ru.adsrv.frpjump.ssh.SshKeyChoice
import ru.adsrv.frpjump.ssh.SshKeySlot
import ru.adsrv.frpjump.ssh.SshLoginMemory

/** The steps of "Use key login": the key choice only with two keys, and the chosen key kept through every later step. */
class KeyLoginFlowTest {
    private val phone = SshKeyChoice(SshKeySlot.PHONE, "ssh-ed25519 AAAAphone frp-jump@phone")
    private val imported = SshKeyChoice(SshKeySlot.IMPORTED, "ssh-ed25519 AAAAimported imported")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Answers each run with the next of [results]; [gate], when set, holds a run until it is completed. */
    private class Installer(val choices: List<SshKeyChoice>, val results: ArrayDeque<KeyInstallResult>) : KeyInstalling {
        val seen = mutableListOf<Pair<KeyInstallTarget, String?>>()
        var gate: CompletableDeferred<Unit>? = null
        override fun keys() = choices
        override suspend fun install(target: KeyInstallTarget, typed: String?): KeyInstallResult {
            seen += target to typed
            gate?.await()
            return results.removeFirst()
        }
        override fun cancel() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    private fun target() = KeyInstallTarget("p", HostKeyStore(MemoryStorage()), "dev", 22, "root", SshLoginMemory(MemoryStorage()), { 1 })

    private fun flow(installer: KeyInstalling) = KeyLoginFlow(installer, scope, Dispatchers.Unconfined)

    private fun await(what: String, done: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!done()) delay(5) }
    }

    @Test
    fun `with two keys the choice is shown first and nothing runs`() {
        val installer = Installer(listOf(phone, imported), ArrayDeque())
        val flow = flow(installer)
        flow.start(target())
        val step = flow.step as KeyLoginStep.Choose
        assertEquals(listOf(phone, imported), step.keys)
        assertTrue(installer.seen.isEmpty())
    }

    @Test
    fun `with one key it goes straight to installing it`() {
        val installer = Installer(listOf(phone), ArrayDeque(listOf(KeyInstallResult.Installed)))
        installer.gate = CompletableDeferred()
        val flow = flow(installer)
        flow.start(target())
        assertTrue(flow.step is KeyLoginStep.Working)
        assertEquals(phone, installer.seen.single().first.key)
        installer.gate!!.complete(Unit)
        await("done") { flow.step is KeyLoginStep.Done }
    }

    @Test
    fun `a flow that names no keys installs without a choice`() {
        val installer = Installer(emptyList(), ArrayDeque(listOf(KeyInstallResult.Installed)))
        val flow = flow(installer)
        flow.start(target())
        assertNull(installer.seen.single().first.key)
    }

    @Test
    fun `choosing a key runs with it, and cancel in the choice runs nothing`() {
        val installer = Installer(listOf(phone, imported), ArrayDeque(listOf(KeyInstallResult.Installed)))
        val flow = flow(installer)
        flow.start(target())
        flow.dismiss()
        assertNull(flow.step)
        assertTrue(installer.seen.isEmpty())
        flow.start(target())
        flow.choose(imported)
        assertEquals(imported, installer.seen.single().first.key)
    }

    @Test
    fun `the chosen key stays through the password, a changed user, the host key and a retry`() {
        val installer = Installer(
            listOf(phone, imported),
            ArrayDeque(
                listOf(
                    KeyInstallResult.PasswordNeeded(null),
                    KeyInstallResult.PasswordNeeded(LoginRefusal.PASSWORD_TYPED),
                    KeyInstallResult.HostKey(HostKeyCheck.Unknown, PinnedHostKey("t", "f")),
                    KeyInstallResult.CommandFailed("x"),
                    KeyInstallResult.ConnectionFailed("y"),
                ),
            ),
        )
        val flow = flow(installer)
        flow.start(target())
        flow.choose(imported)
        assertTrue(flow.step is KeyLoginStep.AskPassword)
        flow.password("other", "pw")
        assertTrue(flow.step is KeyLoginStep.AskPassword)
        flow.password("other", "pw2")
        assertTrue(flow.step is KeyLoginStep.HostKey)
        flow.hostKeyAnswered()
        await("after the host key") { flow.step is KeyLoginStep.Done }
        flow.retry()
        assertEquals(5, installer.seen.size)
        assertTrue(installer.seen.all { it.first.key == imported })
        assertEquals(listOf("root", "other", "other", "other", "other"), installer.seen.map { it.first.user })
    }
}
