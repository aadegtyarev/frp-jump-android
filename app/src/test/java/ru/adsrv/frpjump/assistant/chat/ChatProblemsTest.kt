package ru.adsrv.frpjump.assistant.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.ProviderSetupException
import ru.adsrv.frpjump.assistant.ProviderSetupProblem

/** The oracles are the former inline `when` expressions of `AssistantConversation`, copied verbatim. */
class ChatProblemsTest {
    private fun oldFinish(summaryProblem: ChatProblem?, finish: AgentFinish, transportTimeout: Boolean, invalidReply: Boolean): ChatProblem? =
        summaryProblem ?: when (finish) {
            AgentFinish.COMPLETE -> null; AgentFinish.CONTEXT_FULL -> ChatProblem.CONTEXT_FULL; AgentFinish.MODEL_TIMEOUT -> ChatProblem.TIMEOUT
            AgentFinish.INVALID_REPLY, AgentFinish.STEP_LIMIT -> ChatProblem.INVALID_REPLY
            else -> if (transportTimeout) ChatProblem.TIMEOUT else if (invalidReply) ChatProblem.INVALID_REPLY else ChatProblem.PROVIDER
        }
    private fun oldSummary(failure: Exception) = when ((failure as? ProviderSetupException)?.problem) {
        ProviderSetupProblem.CRYPTO_FAILED, ProviderSetupProblem.ADDRESS_CHANGED, ProviderSetupProblem.INVALID_KEY -> ChatProblem.KEY
        ProviderSetupProblem.READ_FAILED -> ChatProblem.READ
        else -> ChatProblem.SUMMARY
    }
    private fun oldSetup(failure: Exception) = when ((failure as? ProviderSetupException)?.problem) {
        ProviderSetupProblem.CRYPTO_FAILED, ProviderSetupProblem.ADDRESS_CHANGED, ProviderSetupProblem.INVALID_KEY -> ChatProblem.KEY
        ProviderSetupProblem.READ_FAILED -> ChatProblem.READ; else -> ChatProblem.SETUP
    }
    private val failures: List<Exception> = ProviderSetupProblem.entries.map { ProviderSetupException(it) } +
        listOf(IllegalStateException(), ProviderException(ProviderErrorKind.TIMEOUT, null, null))

    @Test fun `finish problem matches the former mapping for every finish flag and summary problem`() {
        for (summary in listOf(null) + ChatProblem.entries) for (finish in AgentFinish.entries)
            for (timeout in listOf(false, true)) for (invalid in listOf(false, true))
                assertEquals("$summary $finish $timeout $invalid", oldFinish(summary, finish, timeout, invalid), finishProblem(summary, finish, timeout, invalid))
    }
    @Test fun `known finishes`() {
        assertEquals(null, finishProblem(null, AgentFinish.COMPLETE, true, true))
        assertEquals(ChatProblem.TIMEOUT, finishProblem(null, AgentFinish.PROVIDER_FAILED, true, true))
        assertEquals(ChatProblem.INVALID_REPLY, finishProblem(null, AgentFinish.PROVIDER_FAILED, false, true))
        assertEquals(ChatProblem.PROVIDER, finishProblem(null, AgentFinish.DISABLED, false, false))
        assertEquals(ChatProblem.SUMMARY, finishProblem(ChatProblem.SUMMARY, AgentFinish.COMPLETE, false, false))
    }
    @Test fun `setup mapping matches the former expressions for every setup problem and other failures`() {
        for (failure in failures) {
            assertEquals("$failure", oldSetup(failure), setupProblem(failure, ChatProblem.SETUP))
            assertEquals("$failure", oldSummary(failure), setupProblem(failure, ChatProblem.SUMMARY))
        }
    }
    @Test fun `setup mapping fallbacks differ only for unclassified failures`() {
        assertEquals(ChatProblem.SETUP, setupProblem(IllegalStateException(), ChatProblem.SETUP))
        assertEquals(ChatProblem.SUMMARY, setupProblem(IllegalStateException(), ChatProblem.SUMMARY))
        assertEquals(ChatProblem.KEY, setupProblem(ProviderSetupException(ProviderSetupProblem.INVALID_KEY), ChatProblem.SUMMARY))
        assertEquals(ChatProblem.READ, setupProblem(ProviderSetupException(ProviderSetupProblem.READ_FAILED), ChatProblem.SETUP))
        assertEquals(ChatProblem.SETUP, setupProblem(ProviderSetupException(ProviderSetupProblem.STALE), ChatProblem.SETUP))
    }
    @Test fun `transport kinds match the former flags`() {
        for (kind in ProviderErrorKind.entries) {
            assertEquals("$kind", kind == ProviderErrorKind.TIMEOUT, isTransportTimeout(kind))
            assertEquals("$kind", kind in setOf(ProviderErrorKind.INVALID_RESPONSE, ProviderErrorKind.INCOMPLETE_RESPONSE, ProviderErrorKind.RESPONSE_TOO_LARGE), isInvalidReply(kind))
        }
    }
}
