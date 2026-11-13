package ru.adsrv.frpjump.ui

import androidx.navigation.NavHostController

/**
 * The back stack (routes, bottom to top) after Settings is opened: one Settings only. With it already in the stack the screens
 * above it are closed and it is the top; otherwise it is pushed.
 */
internal fun stackAfterOpeningSettings(stack: List<String?>): List<String?> {
    val at = stack.lastIndexOf(Routes.SETTINGS)
    return if (at >= 0) stack.take(at + 1) else stack + Routes.SETTINGS
}

/** The one way into Settings, from every path: pops to the Settings entry in the stack, else pushes it (`launchSingleTop`). */
internal fun NavHostController.openSettings() {
    val inStack = runCatching { getBackStackEntry(Routes.SETTINGS) }.isSuccess
    if (inStack) popBackStack(Routes.SETTINGS, inclusive = false)
    else navigate(Routes.SETTINGS) { launchSingleTop = true }
}
