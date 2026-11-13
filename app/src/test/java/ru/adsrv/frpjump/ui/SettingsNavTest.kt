package ru.adsrv.frpjump.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings is one screen in the back stack, reached by one helper; no bottom-navigation item leads to it any more. */
class SettingsNavTest {
    @Test fun settingsIsPushedOnTheSectionItWasOpenedFrom() {
        assertEquals(listOf(Routes.DEVICES, Routes.SETTINGS), stackAfterOpeningSettings(listOf(Routes.DEVICES)))
        assertEquals(listOf(Routes.DEVICES, Routes.CONNECTIONS, Routes.SETTINGS), stackAfterOpeningSettings(listOf(Routes.DEVICES, Routes.CONNECTIONS)))
    }

    @Test fun openingItAgainReturnsToTheExistingEntryAndClosesWhatIsAboveIt() {
        val open = listOf(Routes.DEVICES, Routes.SETTINGS)
        assertEquals(open, stackAfterOpeningSettings(open))
        assertEquals(open, stackAfterOpeningSettings(open + Routes.CHAT + Routes.PROVIDER))
        assertEquals(1, stackAfterOpeningSettings(open + Routes.CHAT).count { it == Routes.SETTINGS })
    }

    @Test fun backFromSettingsLeavesTheSectionItWasOpenedFrom() {
        val stack = stackAfterOpeningSettings(listOf(Routes.DEVICES, Routes.SESSIONS))
        assertEquals(listOf(Routes.DEVICES, Routes.SESSIONS), stack.dropLast(1))
    }

    @Test fun settingsIsNotATab() {
        assertEquals(listOf("DEVICES", "CONNECTIONS", "SESSIONS"), Tab.entries.map { it.name })
        assertTrue(Routes.SETTINGS == "settings")
    }

    /** A tripwire, not a proof: the old ways into Settings (a tab frame, the tab switch) are not written again. */
    @Test fun noTabFrameOrTabSwitchOfSettingsRemains() {
        val root = listOf(File("src"), File("app/src")).first { it.isDirectory }
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "SettingsNavTest.kt" }
            .filter { f -> f.readText().let { "TabFrame(Tab.SETTINGS" in it || "goToTab(Tab.SETTINGS" in it || "Tab.SETTINGS" in it } }
            .map { it.path }.toList()
        assertFalse("the Settings tab is back in: $offenders", offenders.isNotEmpty())
    }
}
