package ru.adsrv.frpjump.direct

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No "Direct didn't work" notification (operator decision Р34): its channel is deleted, and nothing posts to it. */
class DirectChannelTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun `retiring deletes the direct channel, which clears its shown notifications`() {
        val deleted = mutableListOf<String>()
        DirectChannel.retire { deleted += it }
        assertEquals(listOf("direct"), deleted)
    }

    @Test
    fun `a failing delete does not stop the start`() {
        DirectChannel.retire { error("no service") }
    }

    @Test
    fun `nothing creates the channel or posts to it, and no receiver is left`() {
        val sources = File(root, "java").walkTopDown().filter { it.extension == "kt" }.toList()
        val offenders = sources.filter { f ->
            val text = f.readText()
            f.name != "DirectChannel.kt" && (text.contains("DirectChannel.ID") || Regex("""createNotificationChannel[\s\S]{0,200}"direct"""").containsMatchIn(text))
        }
        assertEquals(emptyList<File>(), offenders)
        assertTrue(sources.none { it.name == "DirectActionReceiver.kt" || it.name == "DirectNotifier.kt" })
        assertFalse(File(root, "AndroidManifest.xml").readText().contains("DirectActionReceiver"))
    }
}
