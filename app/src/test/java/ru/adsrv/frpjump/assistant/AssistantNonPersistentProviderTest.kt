package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

class AssistantNonPersistentProviderTest {
    private class Local(override val estimatedTokens: Long = 3, override val sizeChars: Int = 7) : AgentContinuation
    private val plain = AgentProvider { AgentReply("x") }
    private val persistent = object : AgentProvider, PersistentContinuations {
        override suspend fun complete(request: AgentRequest) = AgentReply("x")
        override fun saveContinuation(value: AgentContinuation) = "saved"
        override fun restoreContinuation(saved: String): AgentContinuation = Local(1, saved.length)
    }

    @Test fun `non persistent provider keeps continuation local and saves no string`() {
        val local = Local()
        val saved = SavedReply.of(AgentReply("t", continuation = local), plain)
        assertNull(saved.continuation)
        assertSame(local, saved.localContinuation)
        assertSame(local, saved.value(plain).continuation)
    }

    @Test fun `restoring a saved string with a non persistent provider fails`() {
        assertThrows(IllegalStateException::class.java) { SavedReply("t", continuation = "saved").value(plain) }
    }

    @Test fun `persistent provider saves and restores a string and keeps nothing local`() {
        val saved = SavedReply.of(AgentReply("t", continuation = Local()), persistent)
        assertEquals("saved", saved.continuation)
        assertNull(saved.localContinuation)
        assertEquals(5, saved.value(persistent).continuation?.sizeChars)
    }
}
