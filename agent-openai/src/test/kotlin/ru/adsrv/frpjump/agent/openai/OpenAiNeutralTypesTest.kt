package ru.adsrv.frpjump.agent.openai

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.*

class OpenAiNeutralTypesTest {
    @Test fun `provider failure maps one to one by name onto the neutral kind`() {
        val names = listOf("HTTP", "NETWORK", "TIMEOUT", "INCOMPATIBLE_CONTEXT", "RESPONSE_TOO_LARGE", "REQUEST_TOO_LARGE", "INVALID_RESPONSE", "INCOMPLETE_RESPONSE")
        assertEquals(names, ProviderErrorKind.entries.map { it.name })
        assertEquals(names, ProviderFailure.entries.map { it.name })
        for (kind in ProviderErrorKind.entries) assertSame(kind, ProviderFailure.valueOf(kind.name))
    }

    @Test fun `OpenAI exception is a neutral provider exception with the same kind status and message`() {
        val error: ProviderException = OpenAiProviderException(ProviderFailure.HTTP, 429)
        assertTrue(error is OpenAiProviderException)
        assertEquals(ProviderErrorKind.HTTP, error.kind)
        assertEquals(429, error.status)
        assertEquals("Provider failure: HTTP (429)", error.message)
        assertEquals(ProviderFailure.HTTP, (error as OpenAiProviderException).failure)
        val bare = OpenAiProviderException(ProviderFailure.TIMEOUT)
        assertNull(bare.status); assertEquals("Provider failure: TIMEOUT", bare.message)
    }

    @Test fun `OpenAI provider exposes the neutral optional capabilities`() {
        val provider: AgentProvider = OpenAiAgentProvider(OpenAiConfig("https://example.org/v1/", "m"), "synthetic")
        assertTrue(provider is PersistentContinuations)
        assertTrue(provider is ImageInspector)
    }
}
