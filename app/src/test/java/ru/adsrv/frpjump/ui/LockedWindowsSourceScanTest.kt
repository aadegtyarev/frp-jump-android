package ru.adsrv.frpjump.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every window of the app is made through the wrappers of `ui/LockedWindows.kt`, which leave it out while the
 * app is locked. A direct use of a window API anywhere else would draw over the lock screen, so it fails here.
 */
class LockedWindowsSourceScanTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val wrappers = File(root, "ru/adsrv/frpjump/ui/LockedWindows.kt")

    private val windowNames = "AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet|Popup|DropdownMenu|ExposedDropdownMenuBox|" +
        "ExposedDropdownMenu|DatePickerDialog|TimePickerDialog|PopupWindow|PopupMenu|BottomSheetDialog|DialogFragment"

    /** A call (or a qualified call) of a window API by its own name. */
    private val call = Regex("""(?<![A-Za-z0-9_])($windowNames)\s*\(""")
    private val builder = Regex("""AlertDialog\s*\.\s*Builder|Dialog\s*\.\s*Builder|\bshowAsDropDown\b|\bshowAtLocation\b""")
    private val imports = Regex(
        """^import\s+(androidx\.compose\.material3\.($windowNames)|androidx\.compose\.ui\.window\.(Dialog|Popup)|""" +
            """android\.app\.(AlertDialog|Dialog|DatePickerDialog|TimePickerDialog)|android\.widget\.(PopupWindow|PopupMenu|ListPopupWindow)|""" +
            """com\.google\.android\.material\.bottomsheet\.\w+)\s*$""",
        RegexOption.MULTILINE,
    )

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { it.replace(Regex("""(?<!:)//.*"""), "") }

    private fun problems(name: String, source: String): List<String> {
        val code = stripComments(source)
        return (call.findAll(code) + builder.findAll(code) + imports.findAll(code)).map { "$name: ${it.value.trim()}" }.toList()
    }

    @Test fun noDirectWindowApiOutsideTheWrappers() {
        val sources = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") && it != wrappers }.toList()
        assertTrue("sources not found at $root", sources.size > 100 && wrappers.isFile)
        val found = sources.flatMap { problems(it.relativeTo(root).path, it.readText()) }
        assertEquals(
            "A window made directly can show over the lock screen. Use AppAlertDialog, AppDialog, AppModalBottomSheet, AppPopup or AppDropdownMenu:\n" +
                found.joinToString("\n"),
            emptyList<String>(), found,
        )
    }

    @Test fun theScanSeesDirectUses() {
        val samples = listOf(
            "AlertDialog(onDismissRequest = {}, confirmButton = {})",
            "androidx.compose.material3.DropdownMenu(expanded = true) {}",
            "import androidx.compose.ui.window.Popup",
            "import androidx.compose.material3.ModalBottomSheet",
            "androidx.appcompat.app.AlertDialog.Builder(context).show()",
            "PopupWindow(view)",
        )
        samples.forEach { assertTrue("missed: $it", problems("sample", it).isNotEmpty()) }
        val fine = listOf(
            "AppAlertDialog(onDismissRequest = {})", "import androidx.compose.ui.window.DialogProperties",
            "SshLoginDialog(", "DropdownMenuItem(", "// AlertDialog(", "/** a Dialog( */",
        )
        fine.forEach { assertTrue("false alarm: $it", problems("sample", it).isEmpty()) }
    }

    /** WebView's own question windows would show over the lock screen: they are handled in-app and held while locked. */
    @Test fun webViewJavaScriptDialogsAreHandled() {
        val web = stripComments(File(root, "ru/adsrv/frpjump/ui/web/WebScreen.kt").readText())
        listOf("onJsAlert", "onJsConfirm", "onJsPrompt", "onJsBeforeUnload").forEach {
            assertTrue("$it is not overridden: WebView would show its own window", Regex("""override fun $it\(""").containsMatchIn(web))
        }
    }
}
