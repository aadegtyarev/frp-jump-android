package ru.adsrv.frpjump.direct

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.store.TextStorage

/** No "Direct didn't work" notification: the old channel and offers file are removed at start, and nothing posts. */
class DirectChannelTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val kotlin = File(root, "java")

    private class Offers(var failDelete: Boolean = false) : TextStorage {
        var deleted = false
        override fun read(): String? = null
        override fun write(text: String) = Unit
        override fun delete() {
            if (failDelete) error("disk")
            deleted = true
        }
    }

    @Test
    fun `retiring deletes the direct channel and the offers file`() {
        val channels = mutableListOf<String>()
        val offers = Offers()
        DirectChannel.retireLegacy({ channels += it }, offers)
        assertEquals(listOf("direct"), channels)
        assertTrue(offers.deleted)
    }

    @Test
    fun `a failing channel delete still removes the file, and a failing file delete does not break start`() {
        val offers = Offers()
        DirectChannel.retireLegacy({ error("no service") }, offers)
        assertTrue(offers.deleted)
        val channels = mutableListOf<String>()
        DirectChannel.retireLegacy({ channels += it }, Offers(failDelete = true))
        assertEquals(listOf("direct"), channels)
    }

    @Test
    fun `the app calls the cleanup, through the atomic file so its leftovers go too`() {
        val app = File(kotlin, "ru/adsrv/frpjump/FrpJumpApp.kt").readText()
        assertTrue("called unconditionally", Regex("""(?m)^\s*DirectChannel\.retireLegacy\(""").containsMatchIn(app))
        assertTrue(app.contains("""AtomicTextFile(File(filesDir, "direct_offered.json"))"""))
        assertFalse(app.contains("""File(filesDir, "direct_offered.json").delete()"""))
    }

    @Test
    fun `the direct channel id is named in one place, and the direct package posts nothing`() {
        val sources = kotlin.walkTopDown().filter { it.extension == "kt" }.toList()
        val holders = sources.filter { it.readText().contains("\"direct\"") }.map { it.relativeTo(kotlin).path }.sorted()
        assertEquals(listOf("ru/adsrv/frpjump/direct/DirectChannel.kt", "ru/adsrv/frpjump/settings/AppSettings.kt"), holders)
        val direct = sources.filter { it.parentFile.name == "direct" }
        assertTrue(direct.isNotEmpty())
        direct.forEach { f ->
            val text = f.readText()
            assertFalse("${f.name} posts", text.contains("NotificationCompat.Builder(") || text.contains(".notify("))
        }
        assertTrue(sources.none { it.name == "DirectActionReceiver.kt" || it.name == "DirectNotifier.kt" })
        assertFalse(File(root, "AndroidManifest.xml").readText().contains("DirectActionReceiver"))
    }
}
