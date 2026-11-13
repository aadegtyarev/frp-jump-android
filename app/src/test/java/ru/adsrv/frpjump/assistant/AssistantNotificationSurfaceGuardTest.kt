package ru.adsrv.frpjump.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Р32 part B, Р41, Р47: the notification's components are private to the app and MainActivity takes no navigation from a foreign intent. */
class AssistantNotificationSurfaceGuardTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val main = File("src/main/java/ru/adsrv/frpjump/MainActivity.kt").readText()
    private val notifier = File("src/main/java/ru/adsrv/frpjump/assistant/AssistantNotifier.kt").readText()

    private fun element(tag: String, name: String): String {
        val start = manifest.indexOf("<$tag\n            android:name=\"$name\"")
        assertTrue("$name declared", start >= 0)
        val selfClosing = manifest.indexOf("/>", start); val open = manifest.indexOf(">", start)
        val end = if (selfClosing == open - 1) selfClosing + 2 else manifest.indexOf("</$tag>", start) + tag.length + 3
        return manifest.substring(start, end)
    }

    @Test fun `the Stop receiver and the tap trampoline are not exported and have no intent filter`() {
        for ((tag, name) in listOf("receiver" to ".assistant.AssistantStopReceiver", "activity" to ".assistant.AssistantOpenActivity")) {
            val e = element(tag, name)
            assertTrue("$name exported=false", e.contains("android:exported=\"false\""))
            assertFalse("$name exported=true", e.contains("android:exported=\"true\""))
            assertFalse("$name has no intent-filter", e.contains("<intent-filter"))
        }
        val trampoline = element("activity", ".assistant.AssistantOpenActivity")
        assertTrue(trampoline.contains("android:excludeFromRecents=\"true\"")); assertTrue(trampoline.contains("android:noHistory=\"true\""))
        assertTrue(trampoline.contains("Translucent.NoTitleBar"))
    }

    @Test fun `the PendingIntents are immutable and explicit, a broadcast for Stop and an activity for the tap`() {
        assertTrue(notifier.contains("PendingIntent.getBroadcast(") && notifier.contains("Intent(context, AssistantStopReceiver::class.java)"))
        assertTrue(notifier.contains("PendingIntent.getActivity(") && notifier.contains("Intent(context, AssistantOpenActivity::class.java)"))
        assertEquals(2, Regex("FLAG_IMMUTABLE").findAll(notifier).count())
        assertFalse(notifier.contains("FLAG_MUTABLE")); assertFalse(notifier.contains("getService("))
    }

    @Test fun `the receiver only stops the attempt and the trampoline starts MainActivity with a plain intent`() {
        val receiver = notifier.substringAfter("class AssistantStopReceiver").substringBefore("class AssistantOpenActivity")
        assertTrue(receiver.contains("stopAssistantAttempt()"))
        assertFalse(receiver.contains("intent.")); assertFalse(receiver.contains("getStringExtra"))
        val trampoline = notifier.substringAfter("class AssistantOpenActivity")
        assertTrue(trampoline.contains("Intent(this, MainActivity::class.java)"))
        assertFalse(trampoline.contains("putExtra")); assertFalse(trampoline.contains("setAction")); assertFalse(trampoline.contains("setData"))
    }

    @Test fun `MainActivity reads no extras, action or data of an intent for navigation`() {
        for (forbidden in listOf("getStringExtra", "getExtra", "getIntExtra", "getBooleanExtra", "intent.data", "intent?.data", "intent?.action", "getIntent",
            "this.intent", "onNewIntent", "ACTION_OPEN", "takeOpen", ".extras", "getParcelableExtra", "getStringArrayExtra", "getLongExtra"))
            assertFalse("MainActivity must not use $forbidden", main.contains(forbidden))
        // The two reads of an `intent.action` are the outgoing launches in startActivity and startActivityForResult (the lock's hold), not the launch intent.
        assertEquals(2, Regex("intent\\.action").findAll(main).count())
        assertTrue(main.substringBefore("override fun startActivity(").let { !it.contains("intent.action") })
    }
}
