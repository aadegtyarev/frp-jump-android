package ru.adsrv.frpjump.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A guard against a new window of the app that is made without the wrappers of `ui/LockedWindows.kt`
 * (which leave it out while the app is locked). It reads sources and is a tripwire, not a proof: it flags any
 * identifier of a window API in code (strings and comments stripped), with or without a call, an alias import,
 * a wildcard import, a qualified name, a typealias or a function reference. A window made by other means
 * (reflection, a window API not listed here) is not seen, so a new window API is added to [windowNames].
 */
class LockedWindowsSourceScanTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val wrappers = File(root, "ru/adsrv/frpjump/ui/LockedWindows.kt")

    /**
     * The one use that stays: the notice fallback, a system toast, made only while no screen of the app is started
     * (`Notices` drops messages while the app is locked, and a toast is system-drawn, not a window of the app).
     */
    private val allowed = mapOf("ru/adsrv/frpjump/FrpJumpApp.kt" to setOf("Toast"))

    private val windowNames = listOf(
        "AlertDialog", "BasicAlertDialog", "Dialog", "ModalBottomSheet", "Popup", "DropdownMenu", "ExposedDropdownMenuBox",
        "ExposedDropdownMenu", "DatePickerDialog", "TimePickerDialog", "PopupWindow", "ListPopupWindow", "PopupMenu",
        "TooltipBox", "BottomSheetDialog", "DialogFragment", "Toast",
    ).joinToString("|")

    /** The name as an identifier on its own: a call, `Popup {`, `::Popup`, `= PopupWindow`, an alias import. */
    private val bare = Regex("""(?<![\w.])($windowNames)(?!\w)""")

    /** The name behind a package that has it, with or without a call: `androidx.compose.ui.window.Popup {`, an import. */
    private val qualified = Regex(
        """\b(?:androidx\.compose\.material3|androidx\.compose\.ui\.window|android\.app|android\.widget|android\.view|""" +
            """androidx\.appcompat\.app|androidx\.fragment\.app|com\.google\.android\.material\.\w+)\.($windowNames)(?!\w)""",
    )

    /** Windows added by hand. */
    private val manual = Regex("""(?i)\bwindowManager\w*\s*\.\s*(addView|updateViewLayout)\b|\bshowAsDropDown\b|\bshowAtLocation\b""")

    private fun stripped(text: String): String {
        var code = text.replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")
        code = code.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        return code.lines().joinToString("\n") { line ->
            line.replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"").replace(Regex("""(?<!:)//.*"""), "")
        }
    }

    private fun problems(name: String, source: String): List<String> {
        val code = stripped(source)
        val skip = allowed[name].orEmpty()
        return (bare.findAll(code) + qualified.findAll(code) + manual.findAll(code))
            .map { it.value.trim() }
            .filter { hit -> skip.none { hit.endsWith(it) } }
            .map { "$name: $it" }
            .toList()
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
            // the bypasses a call-only scan misses
            "import androidx.compose.material3.AlertDialog as SystemDialog",
            "import androidx.compose.ui.window.*\\n\\nfun f() { Popup { } }",
            "androidx.compose.ui.window.Popup { }",
            "androidx.compose.ui.window.Dialog(onDismissRequest = {}) {}",
            "TooltipBox(positionProvider, tooltip, state) { }",
            "val p = ListPopupWindow(context)",
            "typealias Pop = PopupWindow",
            "val make = ::Popup",
            "val f = ::AlertDialog",
            "windowManager.addView(view, params)",
            "context.getSystemService(WindowManager::class.java).let { wm -> wm }; windowManagerService.addView(v, p)",
            "Toast.makeText(context, \"x\", Toast.LENGTH_SHORT).show()",
            "DropdownMenu {",
            "ModalBottomSheet(onDismissRequest = {}) { }",
        )
        samples.forEach { assertTrue("missed: $it", problems("sample", it).isNotEmpty()) }
        val fine = listOf(
            "AppAlertDialog(onDismissRequest = {})", "import androidx.compose.ui.window.DialogProperties",
            "SshLoginDialog(", "DropdownMenuItem(", "// AlertDialog(", "/** a Dialog( */",
            "val text = \"a Dialog( and a Popup { in a string\"", "val raw = \"\"\"Popup {\n AlertDialog(\"\"\"",
            "AppPopup { }", "terminal.addView(view)", "import androidx.compose.ui.window.PopupProperties",
        )
        fine.forEach { assertTrue("false alarm: $it -> ${problems("sample", it)}", problems("sample", it).isEmpty()) }
    }

    @Test fun theToastFallbackIsTheOnlyAllowedUse() {
        assertTrue(problems("ru/adsrv/frpjump/FrpJumpApp.kt", "Toast.makeText(this, text, Toast.LENGTH_LONG).show()").isEmpty())
        assertTrue(problems("ru/adsrv/frpjump/other/Elsewhere.kt", "Toast.makeText(this, text, Toast.LENGTH_LONG).show()").isNotEmpty())
        assertTrue(problems("ru/adsrv/frpjump/FrpJumpApp.kt", "PopupWindow(view)").isNotEmpty())
    }

    /** WebView's own question windows would show over the lock screen: they are handled in-app and held while locked. */
    @Test fun webViewJavaScriptDialogsAreHandled() {
        val web = stripped(File(root, "ru/adsrv/frpjump/ui/web/WebScreen.kt").readText())
        listOf("onJsAlert", "onJsConfirm", "onJsPrompt", "onJsBeforeUnload").forEach {
            assertTrue("$it is not overridden: WebView would show its own window", Regex("""override fun $it\(""").containsMatchIn(web))
            assertTrue("$it must return true: false lets WebView show its own window", Regex("""override fun $it\([\s\S]*?return true""").containsMatchIn(web))
        }
    }

    /** The wiring in WebScreen that a device test of `configure()` cannot see: a new page and the end of the screen. */
    @Test fun webScreenWiresTheDialogsToThePageAndTheScreen() {
        val web = stripped(File(root, "ru/adsrv/frpjump/ui/web/WebScreen.kt").readText())
        assertTrue("a page start must reach jsDialogs.onPage", web.contains("jsDialogs.onPage(event)"))
        assertTrue("leaving the screen must cancel what waits", Regex("""onDispose \{[\s\S]*?jsDialogs\.cancelPending\(\)""").containsMatchIn(web))
        assertTrue("the dialogs must be shown by the host", web.contains("JsDialogHost(jsDialogs)"))
    }

    /** MainActivity provides the lock to the windows and keeps the keyboard off while locked. */
    @Test fun mainActivityProvidesTheLockAndKeepsTheKeyboardOff() {
        val main = stripped(File(root, "ru/adsrv/frpjump/MainActivity.kt").readText())
        assertTrue(main.contains("LocalAppLocked provides locked"))
        assertTrue(main.contains("FLAG_ALT_FOCUSABLE_IM"))
        assertTrue(Regex("""blockIme\(locked\)""").containsMatchIn(main))
    }
}
