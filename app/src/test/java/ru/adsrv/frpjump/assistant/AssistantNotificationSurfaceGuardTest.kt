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
        assertTrue("the launcher intent", trampoline.contains("setAction(Intent.ACTION_MAIN)") && trampoline.contains("addCategory(Intent.CATEGORY_LAUNCHER)"))
        assertTrue(trampoline.contains("FLAG_ACTIVITY_NEW_TASK") && trampoline.contains("FLAG_ACTIVITY_RESET_TASK_IF_NEEDED"))
        assertFalse(trampoline.contains("putExtra")); assertFalse(trampoline.contains("setData")); assertFalse(trampoline.contains("SINGLE_TOP"))
    }

    @Test fun `MainActivity reads no extras, action or data of an intent for navigation`() {
        for (forbidden in listOf("getStringExtra", "getExtra", "getIntExtra", "getBooleanExtra", "intent.data", "intent?.data", "intent?.action", "getIntent",
            "this.intent", "onNewIntent", "ACTION_OPEN", "takeOpen", ".extras", "getParcelableExtra", "getStringArrayExtra", "getLongExtra"))
            assertFalse("MainActivity must not use $forbidden", main.contains(forbidden))
        // The two reads of an `intent.action` are the outgoing launches in startActivity and startActivityForResult (the lock's hold), not the launch intent.
        assertEquals(2, Regex("intent\\.action").findAll(main).count())
        assertTrue(main.substringBefore("override fun startActivity(").let { !it.contains("intent.action") })
    }

    @Test fun `onCreate replaces the launch intent with a clean launcher one before anything composes`() {
        val create = main.substringAfter("override fun onCreate(").substringBefore("setContent")
        val set = create.indexOf("setIntent(")
        assertTrue("setIntent( is the first statement of onCreate", set >= 0 && create.substring(0, set).lines().all { it.isBlank() || it.trim().startsWith("//") || it.contains("Bundle?) {") })
        val line = create.substring(set).lineSequence().first()
        assertTrue(line.contains("Intent(this, MainActivity::class.java)") && line.contains("setAction(Intent.ACTION_MAIN)") && line.contains("addCategory(Intent.CATEGORY_LAUNCHER)"))
        assertFalse("no extras copied", line.contains("putExtra") || line.contains("intent."))
        assertEquals("one setIntent( in the activity", 1, Regex("setIntent\\(").findAll(main).count())
    }

    @Test fun `no screen or the activity reads an incoming intent`() {
        val files = File("src/main/java/ru/adsrv/frpjump/ui").walkTopDown().filter { it.extension == "kt" }.toList() +
            File("src/main/java/ru/adsrv/frpjump/MainActivity.kt")
        val reads = Regex("""\bget\w*Extra\(|hasExtra|\.extras\b|getIntent|\.intent\b|onNewIntent|addOnNewIntentListener|(?<![=\w])\.clipData(?!\s*=)|\bdata\??\.clipData|\.categories|\.flags\b|\bintent\.action|\bintent\.data""")
        // Narrow allowances: the activity-result `data` of a file chooser (a result the app asked for), the sensitive flag the viewer sets on its own outgoing clip, and the lock's outgoing launches.
        val allowed = listOf("val clip = data?.clipData", "clip.description.extras = PersistableBundle()", "holdLaunch(this, intent.action,")
        val found = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null
            else if (reads.containsMatchIn(line) && allowed.none { line.contains(it) }) "${f.name}:${i + 1}: $t" else null
        } }
        assertEquals("incoming intent reads: $found", emptyList<String>(), found)
    }

    @Test fun `the notifier cancels a stale notification before it collects anything, and Stop cancels when no attempt runs`() {
        val start = notifier.substringAfter("fun start(").substringBefore("override fun cancel")
        assertTrue(start.indexOf("driver.start()") in 0 until start.indexOf("scope.launch"))
        val receiver = notifier.substringAfter("class AssistantStopReceiver").substringBefore("class AssistantOpenActivity")
        assertTrue(receiver.contains("ChatActivity.IDLE") && receiver.contains(".cancel(AssistantNotifier.NOTIFICATION_ID)"))
    }
}
