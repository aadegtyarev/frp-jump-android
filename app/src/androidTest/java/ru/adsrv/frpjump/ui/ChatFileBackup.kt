package ru.adsrv.frpjump.ui

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.store.AtomicTextFile
import java.io.File
import java.util.UUID

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

/**
 * The outermost rule of a test that opens the chat: the app's chat file holds a marker for the whole test, and after the activity is
 * gone (the chat's model has been cleared and its last save has had its time) the file must still be the marker. Whatever the test
 * does in its own @After is not enough when the chat is cleared later, with the activity: that save would replace the file the test
 * put back. The device's own chat file is put back at the very end.
 */
internal class ChatFileGuard : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FrpJumpApp
            val file = AtomicTextFile(File(app.noBackupFilesDir, "assistant_chat.json"))
            val backup = ChatFileBackup(app)
            backup.save()
            val marker = "marker-" + UUID.randomUUID()
            file.write(marker)
            try {
                base.evaluate()
                SystemClock.sleep(1_500)
                assertEquals("the chat file was changed after the test ended", marker, file.read())
            } finally { backup.restore() }
        }
    }
}
