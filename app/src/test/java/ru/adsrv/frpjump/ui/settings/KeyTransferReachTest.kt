package ru.adsrv.frpjump.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * К4: the phone key's private half has one way out (the system «Save as», as an encrypted file) and no way to the
 * assistant. This reads sources: a tripwire, not a proof, like LockedWindowsSourceScanTest.
 */
class KeyTransferReachTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val repo = generateSequence(root.absoluteFile) { it.parentFile }.first { File(it, "agent-core").isDirectory }
    private fun source(path: String) = File(root, path).also { assertTrue("$path not found", it.isFile) }.readText()

    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { it.replace(Regex("""(?<!:)//.*"""), "") }

    private val keyFiles = listOf(
        "ru/adsrv/frpjump/ssh/SshKeyExport.kt",
        "ru/adsrv/frpjump/ui/settings/KeyTransfer.kt",
        "ru/adsrv/frpjump/ui/settings/KeyTransferDialogs.kt",
    )

    private val ways = Regex(
        """Clipboard|ClipData|setText\(|ACTION_SEND|createChooser|LocalShareText|shareText|startActivity|Intent\(|\bLog\.|println|Timber|""" +
            """printStackTrace|Logger|FileOutputStream|openFileOutput|getExternalFilesDir|SharedPreferences""",
    )

    @Test fun theKeyExportAndRestoreCodeHasNoClipboardShareIntentLogOrOtherFileWay() {
        keyFiles.forEach { path ->
            val found = ways.findAll(code(source(path))).map { it.value }.toList()
            assertEquals("$path: ${found.joinToString()}", emptyList<String>(), found)
        }
    }

    /** The one write is through the picker's document: the only output stream is the content resolver's. */
    @Test fun theOnlyOutputIsTheContentResolversDocument() {
        val outputs = keyFiles.flatMap { Regex("""openOutputStream\(""").findAll(code(source(it))).toList() }
        assertEquals(1, outputs.size)
        assertTrue(code(source("ru/adsrv/frpjump/ui/settings/KeyTransferDialogs.kt")).contains("context.contentResolver.openOutputStream(uri"))
    }

    /** Every passphrase field of these screens is the strict one, and every passphrase dialog is a secure window. */
    @Test fun everyPassphraseFieldIsStrictAndEveryPassphraseDialogIsSecure() {
        val dialogs = code(source("ru/adsrv/frpjump/ui/settings/KeyTransferDialogs.kt"))
        val fields = Regex("""SecretField\(""").findAll(dialogs).count()
        assertEquals(3, fields)
        assertEquals(fields, Regex("""noPersonalizedLearning = true""").findAll(dialogs).count())
        assertEquals(2, Regex("""properties = SecureDialog""").findAll(dialogs).count())
        assertTrue(dialogs.contains("SecureFlagPolicy.SecureOn"))
    }

    @Test fun theSettingsScreenAddsNoWayForTheKeyEither() {
        val screen = code(source("ru/adsrv/frpjump/ui/settings/SettingsScreen.kt"))
        // Copy and Share of the sheet hand over the PUBLIC line only, which KeyBlock is given.
        assertTrue(screen.contains("KeyBlock(SshKeySlot.PHONE, state.sshPublicKey)"))
        assertTrue(!screen.contains("phoneKeyForExport"))
        assertTrue(!screen.contains("privateKeyText"))
    }

    @Test fun nothingInTheAssistantOrTheAgentModulesReachesExportOrRestore() {
        val names = Regex("""SshKeyExport|KeyTransfer|phoneKeyForExport|replacePhoneKey|privateKeyText|PhoneKey\b""")
        val dirs = listOf(File(root, "ru/adsrv/frpjump/assistant"), File(root, "ru/adsrv/frpjump/ops")) +
            listOf("agent-core", "agent-openai").map { File(repo, "$it/src") }
        var scanned = 0
        val found = dirs.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "md") }.flatMap {
                scanned++
                names.findAll(it.readText()).map { m -> "${it.name}: ${m.value}" }
            }.toList()
        }
        assertTrue("scanned $scanned files", scanned > 50)
        assertEquals(found.joinToString(), emptyList<String>(), found)
    }

    @Test fun onlyTheSettingsModelReadsOrWritesThePhoneKeyForTheseScreens() {
        val users = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\.phoneKeyForExport\(|\.replacePhoneKey\(""").containsMatchIn(code(it.readText())) }
            .map { it.name }.sorted().toList()
        assertEquals(listOf("SettingsViewModel.kt"), users)
    }
}
