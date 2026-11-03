package ru.adsrv.frpjump.assistant

import java.io.IOException
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

class AssistantProviderStoreTest {
    private class File : TextStorage {
        var text: String? = null
        var failRead = false; var failWrite = false; var writes = 0
        override fun read(): String? { if (failRead) throw IOException("synthetic-private-data"); return text }
        override fun write(text: String) { writes++; if (failWrite) throw IOException("synthetic-private-data"); this.text = text }
        override fun delete() { text = null }
    }
    private class Box : SecretBox {
        var opens = 0; var seals = 0; var failSeal = false; var failOpen = false
        override fun seal(plain: String): String {
            seals++; if (failSeal) throw IOException("synthetic-private-data")
            return "sealed:" + Base64.getEncoder().encodeToString(plain.toByteArray())
        }
        override fun open(sealed: String): String {
            opens++; if (failOpen) throw IOException("synthetic-private-data")
            return Base64.getDecoder().decode(sealed.removePrefix("sealed:")).toString(Charsets.UTF_8)
        }
        override fun destroy() = Unit
    }
    private val settings = AssistantProviderSettings("https://example.invalid/v1", "model", "high", 32768, 4096, true)
    private fun problem(expected: ProviderSetupProblem, block: () -> Unit) {
        val error = assertThrows(ProviderSetupException::class.java, block)
        assertEquals(expected, error.problem)
        assertFalse(error.toString().contains("synthetic-private-data")); assertNull(error.cause)
    }
    private fun save(store: AssistantProviderStore, value: AssistantProviderSettings = settings, key: String? = "test-key-one") =
        store.save(AssistantProviderDraft(value, store.state.value.revision, key))

    @Test fun `empty store needs explicit key and does no cryptography on load`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        assertNull(store.state.value.settings); assertEquals(0, box.opens); assertEquals(0, box.seals)
        problem(ProviderSetupProblem.KEY_REQUIRED) { save(store, key = null) }
        assertEquals(0, file.writes)
    }

    @Test fun `one atomic record round trips metadata and sealed key without putting credentials in state`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        assertEquals(settings.normalized(), store.state.value.settings)
        assertEquals(4096, store.state.value.settings!!.limits().outputReserveTokens)
        assertFalse(file.text!!.contains("test-key-one"))
        assertFalse(store.state.value.toString().contains("sealed:"))
        val restored = AssistantProviderStore(file, box)
        assertEquals(store.state.value, restored.state.value); assertEquals(0, box.opens)
        assertFalse(AssistantProviderDraft(settings, null, "test-key-one").toString().contains("test-key-one"))
    }

    @Test fun `same-root metadata changes keep sealed bytes without opening or resealing key`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        save(store, settings.copy(baseUrl = "https://example.invalid/v1/", model = "next", contextTokens = 65536), null)
        assertEquals("next", store.state.value.settings!!.model)
        assertEquals(1, box.seals); assertEquals(0, box.opens)
    }

    @Test fun `saved key is opened only by explicit current-root provider preparation`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        assertEquals(0, box.opens)
        val before = store.state.value
        val provider = store.provider(AssistantProviderDraft(settings, before.revision))
        assertEquals(1, box.opens)
        assertFalse(provider.toString().contains("test-key-one"))
        assertEquals(before, store.state.value)
    }

    @Test fun `changed root cannot reuse saved key for save or discovery`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        val before = file.text; val state = store.state.value
        val draft = AssistantProviderDraft(settings.copy(baseUrl = "https://other.invalid/v1"), state.revision)
        problem(ProviderSetupProblem.ADDRESS_CHANGED) { store.save(draft) }
        problem(ProviderSetupProblem.ADDRESS_CHANGED) { store.provider(draft) }
        assertEquals(before, file.text); assertEquals(state, store.state.value); assertEquals(0, box.opens)
    }

    @Test fun `encrypted credential binds its original root even if plaintext metadata is replaced`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        file.text = file.text!!.replace("example.invalid", "other.invalid")
        val changed = AssistantProviderStore(file, box)
        val state = changed.state.value
        assertEquals("https://other.invalid/v1/", state.settings!!.baseUrl)
        problem(ProviderSetupProblem.ADDRESS_CHANGED) { changed.provider(AssistantProviderDraft(state.settings, state.revision)) }
        changed.provider(AssistantProviderDraft(state.settings, state.revision, "test-key-two"))
    }

    @Test fun `explicit new key allows atomic endpoint and credential replacement`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        val old = store.state.value.revision
        save(store, settings.copy(baseUrl = "https://other.invalid/v1"), "test-key-two")
        assertNotEquals(old, store.state.value.revision)
        assertEquals("https://other.invalid/v1/", AssistantProviderStore(file, box).state.value.settings!!.baseUrl)
        assertFalse(file.text!!.contains("test-key-two")); assertEquals(2, box.seals)
    }

    @Test fun `obsolete revision cannot overwrite create provider or accept old discovery completion`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        val old = store.state.value.revision
        save(store, settings.copy(model = "new"), null)
        val before = file.text
        val stale = AssistantProviderDraft(settings, old, "test-key-two")
        problem(ProviderSetupProblem.STALE) { store.save(stale) }
        problem(ProviderSetupProblem.STALE) { store.provider(stale) }
        problem(ProviderSetupProblem.STALE) { store.requireRevision(old) }
        assertEquals(before, file.text); assertEquals(1, box.seals); assertEquals(0, box.opens)
    }

    @Test fun `read failure never becomes empty and cannot overwrite unknown configuration`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        val before = file.text; val revision = store.state.value.revision; file.failRead = true
        problem(ProviderSetupProblem.READ_FAILED) { store.save(AssistantProviderDraft(settings, revision, "test-key-two")) }
        store.reload(); assertEquals(ProviderSetupProblem.READ_FAILED, store.state.value.problem)
        assertEquals(1, file.writes); assertEquals(1, box.seals)
        file.failRead = false; store.reload()
        assertEquals(revision, store.state.value.revision); assertEquals(before, file.text)
    }

    @Test fun `failed second save retains original document and published metadata`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        val before = file.text; val state = store.state.value; file.failWrite = true
        problem(ProviderSetupProblem.WRITE_FAILED) { save(store, settings.copy(baseUrl = "https://other.invalid/v1"), "test-key-two") }
        assertEquals(before, file.text); assertEquals(state, store.state.value)
        assertEquals(state, AssistantProviderStore(file, box).state.value)
    }

    @Test fun `crypto failure is redacted and a replacement does not need to decrypt an unreadable key`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        save(store)
        val before = file.text; box.failSeal = true
        problem(ProviderSetupProblem.CRYPTO_FAILED) { save(store, key = "test-key-two") }
        assertEquals(before, file.text)
        box.failSeal = false; box.failOpen = true
        problem(ProviderSetupProblem.CRYPTO_FAILED) { store.provider(AssistantProviderDraft(settings, store.state.value.revision)) }
        save(store, key = "test-key-two")
        assertEquals(1, box.opens)
    }

    @Test fun `malformed or unsupported data is unreadable and not writable as an empty profile`() {
        for (data in listOf("not json", "{}", "[]", "x".repeat(65_537))) {
            val file = File().apply { text = data }; val box = Box(); val store = AssistantProviderStore(file, box)
            assertEquals(ProviderSetupProblem.READ_FAILED, store.state.value.problem)
            problem(ProviderSetupProblem.READ_FAILED) { save(store) }
            assertEquals(0, file.writes); assertEquals(0, box.seals)
        }
    }

    @Test fun `invalid settings or key cannot seal or mutate the file`() {
        val file = File(); val box = Box(); val store = AssistantProviderStore(file, box)
        problem(ProviderSetupProblem.INVALID_SETTINGS) { save(store, settings.copy(outputTokens = settings.contextTokens)) }
        problem(ProviderSetupProblem.INVALID_SETTINGS) { save(store, settings.copy(baseUrl = "https://example.invalid/v1?key=not-allowed")) }
        problem(ProviderSetupProblem.INVALID_KEY) { save(store, key = "bad\nvalue") }
        assertEquals(0, file.writes); assertEquals(0, box.seals)
    }

    @Test fun `fresh storage read detects an external replacement even if observer still has old snapshot`() {
        val file = File(); val box = Box(); val first = AssistantProviderStore(file, box)
        save(first)
        val old = first.state.value
        val other = AssistantProviderStore(file, box)
        save(other, settings.copy(model = "external"), null)
        problem(ProviderSetupProblem.STALE) { first.provider(AssistantProviderDraft(settings, old.revision)) }
        assertEquals(old, first.state.value)
    }
}
