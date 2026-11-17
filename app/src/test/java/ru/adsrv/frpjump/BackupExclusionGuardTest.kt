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
}
