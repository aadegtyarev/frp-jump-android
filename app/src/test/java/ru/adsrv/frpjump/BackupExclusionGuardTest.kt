package ru.adsrv.frpjump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Nothing of the app's data leaves the phone by cloud backup or by device-to-device transfer (Android 12+ reads dataExtractionRules). */
class BackupExclusionGuardTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
    private val allDomains = listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")

    @Test fun `the manifest turns every backup path off and points at the extraction rules`() {
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test fun `cloud backup and device transfer each exclude every domain and include nothing`() {
        val body = rules.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue("no include rule", !body.contains("<include"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val start = body.indexOf("<$section>"); val end = body.indexOf("</$section>")
            assertTrue("$section declared", start >= 0 && end > start)
            val excluded = Regex("<exclude domain=\"(\\w+)\" path=\"\\.\" />").findAll(body.substring(start, end)).map { it.groupValues[1] }.toList()
            assertEquals("$section excludes all domains whole", allDomains, excluded)
        }
    }

    @Test fun `the settings file holds the assistant's remembered permissions, lives in the excluded file domain and is never exported`() {
        // settings.json (with assistant_confirmations.overrides) is written to filesDir: the "file" domain, excluded whole from both backup paths above.
        val app = File("src/main/java/ru/adsrv/frpjump/FrpJumpApp.kt").readText()
        assertTrue("the settings file is in filesDir", Regex("""AtomicTextFile\(File\(filesDir, "settings\.json"\)\)""").containsMatchIn(app))
        val body = rules.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        for (section in listOf("cloud-backup", "device-transfer"))
            assertTrue("$section excludes the file domain", body.substring(body.indexOf("<$section>"), body.indexOf("</$section>")).contains("<exclude domain=\"file\" path=\".\" />"))
        // A record is only UUIDs, a hash, class and tool names and a time: nothing else is written for it.
        val record = File("src/main/java/ru/adsrv/frpjump/settings/ConfirmationPolicy.kt").readText()
        for (secret in listOf("command", "password", "token", "address")) assertTrue("$secret is not a field of a record", !Regex("""class ConfirmationOverride\([^)]*$secret""", RegexOption.IGNORE_CASE).containsMatchIn(record))
    }
}
