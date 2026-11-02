package com.termux.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.settings.TERMINAL_HISTORY_CHOICES
import ru.adsrv.frpjump.store.TextStorage

/** Real Termux buffers, no SSH client, subprocess or enrolled profile. */
@RunWith(AndroidJUnit4::class)
class TerminalHistoryTest {
    private val client = Proxy.newProxyInstance(
        TerminalSessionClient::class.java.classLoader,
        arrayOf(TerminalSessionClient::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "HistoryTestClient"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as TerminalSessionClient

    @Test fun configuredLimitReachesBothSessionAndEmulator() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (rows in TERMINAL_HISTORY_CHOICES) {
                val terminal = RemoteTerminal(client, historyRows = rows)
                try {
                    assertEquals(rows, terminal.historyRows)
                    assertEquals(rows, terminal.session.emulator.screen.mTotalRows)
                    val field = TerminalSession::class.java.getDeclaredField("mTranscriptRows").apply { isAccessible = true }
                    assertEquals(rows, field.get(terminal.session))
                } finally { terminal.finish() }
            }
        }
    }

    @Test fun changedPreferenceDoesNotResetAnExistingTerminal() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val storage = object : TextStorage {
                var value: String? = null
                override fun read() = value
                override fun write(text: String) { value = text }
                override fun delete() { value = null }
            }
            val settings = SettingsStore(storage)
            val existing = RemoteTerminal(client, historyRows = settings.settings.value.terminalHistoryRows)
            try {
                val text = "kept history\r\n".toByteArray()
                existing.session.emulator.append(text, text.size)
                settings.update { it.copy(terminalHistoryRows = 3000) }
                val fresh = RemoteTerminal(client, historyRows = settings.settings.value.terminalHistoryRows)
                try {
                    assertEquals(1000, existing.historyRows)
                    assertEquals(1000, existing.session.emulator.screen.mTotalRows)
                    assertTrue(existing.session.emulator.screen.transcriptText.contains("kept history"))
                    assertEquals(3000, fresh.session.emulator.screen.mTotalRows)
                } finally { fresh.finish() }
            } finally { existing.finish() }
        }
    }
}
