package com.termux.terminal

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The OSC-only output adapter must not alter manual Copy or other emulator replies/callbacks. */
@RunWith(AndroidJUnit4::class)
class RemoteClipboardRoutingTest {
    private fun verify(split: Boolean) {
        val calls = mutableListOf<String>()
        val interfaces = if (split) arrayOf(TerminalSessionClient::class.java, RemoteClipboardClient::class.java)
            else arrayOf(TerminalSessionClient::class.java)
        val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader, interfaces) { _, method, _ ->
            when (method.name) {
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "SyntheticClipboardRoutingClient"
                else -> { calls += method.name; null }
            }
        } as TerminalSessionClient
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val terminal = RemoteTerminal(client)
            try {
                fun append(text: String) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    terminal.session.emulator.append(bytes, bytes.size)
                }
                append("\u001b]52;c;" + Base64.encodeToString("synthetic".toByteArray(), Base64.NO_WRAP) + "\u0007")
                assertEquals(1, calls.count { it == if (split) "onRemoteCopy" else "onCopyTextToClipboard" })
                if (split) assertEquals(0, calls.count { it == "onCopyTextToClipboard" })
                terminal.session.onCopyTextToClipboard("explicit selection")
                assertEquals(if (split) 1 else 2, calls.count { it == "onCopyTextToClipboard" })
                append("\u0007\u001b]0;synthetic title\u0007\u001b]4;1;#112233\u0007\u001b[5n")
                assertTrue(calls.contains("onBell"))
                assertTrue(calls.contains("onTitleChanged"))
                assertTrue(calls.contains("onColorsChanged"))
                assertEquals("synthetic title", terminal.session.title)
                // DSR uses TerminalOutput.write: still enqueued in the same real session queue.
                val buffer = ByteArray(32)
                val count = terminal.session.mTerminalToProcessIOQueue.read(buffer, false)
                assertEquals("\u001b[0n", String(buffer, 0, count, Charsets.UTF_8))
            } finally { terminal.finish() }
        }
    }

    @Test fun remoteAndManualRoutesAreDistinctAndOtherOutputsDelegate() = verify(split = true)
    @Test fun clientsWithoutRemotePolicyRetainOriginalCopyBehavior() = verify(split = false)
}
