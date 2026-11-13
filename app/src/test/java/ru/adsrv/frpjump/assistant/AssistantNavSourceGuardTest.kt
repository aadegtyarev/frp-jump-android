package ru.adsrv.frpjump.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Source guards for wiring that only the running UI would otherwise exercise (JVM tests run in the module directory): the REMOVED screen
 * stops a running attempt first, and the chat route has no bottom navigation.
 */
class AssistantNavSourceGuardTest {
    private val appNav = File("src/main/java/ru/adsrv/frpjump/ui/AppNav.kt").readText()

    @Test fun `AppNav stops the assistant attempt before it replaces the stack with REMOVED`() {
        val stop = appNav.indexOf("app.stopAssistantAttempt()")
        val replace = appNav.indexOf("nav.replaceAll(Routes.REMOVED)")
        assertTrue("the stop call is present", stop >= 0); assertTrue("the replace call is present", replace >= 0)
        assertTrue("stopAssistantAttempt() comes before replaceAll(Routes.REMOVED)", stop < replace)
    }

    @Test fun `the chat route is not wrapped in the section frame, so the chat has no bottom navigation`() {
        val start = appNav.indexOf("internal fun NavGraphBuilder.assistantRoutes")
        val end = appNav.indexOf("composable(Routes.PROVIDER)", start)
        assertTrue(start >= 0 && end > start)
        val chat = appNav.substring(start, end)
        assertFalse("the chat route must not use TabFrame", chat.contains("TabFrame"))
        assertFalse("the chat route must not draw BottomNav", chat.contains("BottomNav"))
        // And the section frame is where the bottom bar (with the dot) lives.
        assertTrue(appNav.contains("BottomNav(selected, onAssistant"))
    }

    @Test fun `the owner follows the profile removal itself and the app hands it the same flow the REMOVED screen uses`() {
        val app = File("src/main/java/ru/adsrv/frpjump/FrpJumpApp.kt").readText()
        assertTrue(app.contains("removed = currentProfileRemoved"))
        assertTrue(appNav.contains("app.currentProfileRemoved"))
    }

    private fun block(from: String, to: String): String {
        val start = appNav.indexOf(from); val end = appNav.indexOf(to, start + from.length)
        assertTrue("$from found", start >= 0); assertTrue("$to found after $from", end > start)
        return appNav.substring(start, end)
    }

    @Test fun `section screens are reached only through the bottom bar's tab switch or a full stack replace`() {
        // Sections are Devices, Connections and Sessions: TabFrame( appears in those three routes and in its own declaration, nowhere else.
        assertEquals(4, Regex("TabFrame\\(").findAll(appNav).count())
        for ((route, next) in listOf("DEVICES" to "CONNECTIONS", "CONNECTIONS" to "SESSIONS", "SESSIONS" to "DEVICE")) {
            val body = block("composable(Routes.$route)", "composable(Routes.$next")
            assertEquals("TabFrame( only in the $route route", 1, Regex("TabFrame\\(").findAll(body).count())
        }
        val main = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        for (route in listOf("DEVICES", "CONNECTIONS", "SESSIONS"))
            main.forEach { assertFalse("${it.name}: navigate(Routes.$route", it.readText().contains("navigate(Routes.$route")) }
        // Settings is a stack screen with no bottom bar; it is entered by openSettings alone.
        for (f in main) {
            val n = Regex("navigate\\(Routes\\.SETTINGS").findAll(f.readText()).count()
            assertEquals("${f.name}: navigate(Routes.SETTINGS", if (f.name == "SettingsNav.kt") 1 else 0, n)
        }
        val settingsNav = File("src/main/java/ru/adsrv/frpjump/ui/SettingsNav.kt").readText()
        val open = settingsNav.substring(settingsNav.indexOf("fun NavHostController.openSettings"))
        assertTrue(open.contains("navigate(Routes.SETTINGS"))
        assertFalse(block("composable(Routes.SETTINGS)", "internal fun").contains("TabFrame("))
        // Routes.of( is used by goToTab alone.
        assertEquals(1, main.sumOf { Regex("Routes\\.of\\(").findAll(it.readText()).count() })
        val goToTab = block("fun NavHostController.goToTab", "\n}\n")
        assertTrue(goToTab.contains("Routes.of(")); assertTrue(goToTab.contains("popUpTo(Routes.DEVICES)"))
        assertTrue(block("fun NavHostController.replaceAll", "\n}\n").contains("popUpTo(graph.id) { inclusive = true }"))
        // The chat and its provider screen are not section screens.
        assertFalse(block("composable(Routes.CHAT)", "composable(Routes.PROVIDER)").contains("TabFrame("))
        assertFalse(appNav.substring(appNav.indexOf("composable(Routes.PROVIDER)"), appNav.indexOf("internal fun NavHostController.openAssistant")).contains("TabFrame("))
    }
}
