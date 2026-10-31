package ru.adsrv.frpjump.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class SnippetStoreTest {
    private val file = MemoryStorage()
    private val corrupt = MemoryStorage()
    private var next = 0
    private fun store() = SnippetStore(file, corrupt) { "s${next++}" }

    private fun SnippetStore.texts() = snippets.value.map { it.text }

    @Test
    fun `empty by default`() {
        val store = store().apply { load() }
        assertEquals(emptyList<Snippet>(), store.snippets.value)
    }

    @Test
    fun `added in order, kept across restarts`() {
        val store = store()
        store.add("wb-cli --json info")
        store.add("df -h")
        assertEquals(listOf("wb-cli --json info", "df -h"), store.texts())
        val reread = store().apply { load() }
        assertEquals(store.snippets.value, reread.snippets.value)
    }

    @Test
    fun `blank is refused, trailing spaces and line breaks dropped`() {
        val store = store()
        assertNull(store.add("   \n"))
        assertEquals("uptime", store.add("uptime \n")!!.text)
        assertEquals(listOf("uptime"), store.texts())
    }

    @Test
    fun `edit and delete`() {
        val store = store()
        val a = store.add("df -h")!!
        val b = store.add("free -m")!!
        assertTrue(store.update(a.id, "df -h /mnt/data"))
        assertFalse(store.update(a.id, "  "))
        assertFalse(store.update("nope", "ls"))
        assertTrue(store.remove(b.id))
        assertFalse(store.remove(b.id))
        assertEquals(listOf("df -h /mnt/data"), store.texts())
        assertEquals(listOf("df -h /mnt/data"), store().apply { load() }.texts())
    }

    @Test
    fun `reorder up and down, not past either end`() {
        val store = store()
        val a = store.add("a")!!
        store.add("b")
        val c = store.add("c")!!
        assertTrue(store.move(c.id, -1))
        assertEquals(listOf("a", "c", "b"), store.texts())
        assertTrue(store.move(a.id, 2))
        assertEquals(listOf("c", "b", "a"), store.texts())
        assertFalse(store.move(a.id, 1))
        assertFalse(store.move(c.id, -1))
        assertEquals(listOf("c", "b", "a"), store().apply { load() }.texts())
    }

    @Test
    fun `a change before load keeps what was stored`() {
        store().add("first")
        store().add("second")
        assertEquals(listOf("first", "second"), store().apply { load() }.texts())
    }

    @Test
    fun `a file that does not parse is kept aside and reads as empty`() {
        file.text = "{broken"
        val store = store().apply { load() }
        assertEquals(emptyList<Snippet>(), store.snippets.value)
        assertEquals("{broken", corrupt.text)
        store.add("ls")
        assertEquals("{broken", corrupt.text)
    }

    @Test
    fun `long commands are cut to the limit`() {
        val long = "x".repeat(MAX_SNIPPET_LENGTH + 10)
        assertEquals(MAX_SNIPPET_LENGTH, store().add(long)!!.text.length)
    }
}
