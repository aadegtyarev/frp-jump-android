package ru.adsrv.frpjump.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.ui.devices.DeviceCardScreen
import ru.adsrv.frpjump.ui.devices.DevicesScreen
import ru.adsrv.frpjump.ui.devices.DevicesViewModel
import ru.adsrv.frpjump.ui.enroll.EnrollScreen
import ru.adsrv.frpjump.ui.enroll.EnrollViewModel
import ru.adsrv.frpjump.ui.settings.SettingsScreen
import ru.adsrv.frpjump.ui.settings.SettingsViewModel

private object Routes {
    const val ENROLL = "enroll"
    const val DEVICES = "devices"
    const val DEVICE = "device/{name}"
    const val SETTINGS = "settings"
    fun device(name: String) = "device/${Uri.encode(name)}"
}

@Composable
fun AppNav() {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val nav = rememberNavController()
    val start = remember { if (app.store.load() != null) Routes.DEVICES else Routes.ENROLL }
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ENROLL) {
            EnrollScreen(vm = viewModel { EnrollViewModel(app) }, onEnrolled = { nav.replaceAll(Routes.DEVICES) })
        }
        composable(Routes.DEVICES) {
            DevicesScreen(
                vm = viewModel { DevicesViewModel(app) },
                onOpen = { nav.navigate(Routes.device(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DEVICE) { entry ->
            DeviceCardScreen(name = entry.arguments?.getString("name").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = viewModel { SettingsViewModel(app) },
                onBack = { nav.popBackStack() },
                onForgotten = { nav.replaceAll(Routes.ENROLL) },
            )
        }
    }
}

/** Go to [route] with nothing to go back to. */
private fun NavHostController.replaceAll(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
}
