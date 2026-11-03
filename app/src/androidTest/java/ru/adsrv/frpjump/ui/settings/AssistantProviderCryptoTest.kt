package ru.adsrv.frpjump.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.store.KeystoreSecretBox
import ru.adsrv.frpjump.store.TextStorage
import java.util.UUID

/** Actual Android Keystore, isolated ephemeral alias; no real credential or network request. */
@RunWith(AndroidJUnit4::class)
class AssistantProviderCryptoTest {
    @Test fun encryptedCredentialRoundTripsAndBindsItsApiRoot() {
        val box = KeystoreSecretBox("frpjump-agent-provider-test-" + UUID.randomUUID())
        val file = object : TextStorage {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) { this.text = text }
            override fun delete() { text = null }
        }
        try {
            val store = AssistantProviderStore(file, box)
            val settings = AssistantProviderSettings("https://example.invalid/v1/", "manual")
            store.save(AssistantProviderDraft(settings, null, "test-key-crypto"))
            assertFalse(file.text!!.contains("test-key-crypto"))
            val restored = AssistantProviderStore(file, box)
            val state = restored.state.value
            restored.provider(AssistantProviderDraft(state.settings!!, state.revision))
            assertFalse(state.toString().contains("test-key-crypto"))
            file.text = file.text!!.replace("example.invalid", "other.invalid")
            val changed = AssistantProviderStore(file, box).state.value
            val error = assertThrows(ProviderSetupException::class.java) {
                AssistantProviderStore(file, box).provider(AssistantProviderDraft(changed.settings!!, changed.revision))
            }
            assertEquals(ProviderSetupProblem.ADDRESS_CHANGED, error.problem)
        } finally { box.destroy() }
    }
}
