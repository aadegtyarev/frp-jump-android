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
}
