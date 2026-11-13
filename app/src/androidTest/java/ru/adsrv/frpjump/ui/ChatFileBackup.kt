package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.store.AtomicTextFile
import java.io.File

/**
 * Keeps the app's sealed chat file as it was, so a test that needs an empty chat never loses the chat of the device it runs on:
 * [save] before the first change, [restore] after the last (the file is put back byte for byte, or removed when there was none).
 */
internal class ChatFileBackup(app: FrpJumpApp) {
    private val file = AtomicTextFile(File(app.noBackupFilesDir, "assistant_chat.json"))
    private var sealed: String? = null
    private var saved = false

    fun save() { if (!saved) { sealed = file.read(); saved = true } }

    fun restore() {
        if (!saved) return
        sealed?.let { file.write(it) } ?: file.delete()
        saved = false
    }
}
