package ru.adsrv.frpjump.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.store.StoredEnrollment
import ru.adsrv.frpjump.ui.devices.DeviceCardScreen
import ru.adsrv.frpjump.ui.devices.DeviceCardViewModel
import ru.adsrv.frpjump.ui.devices.DevicesScreen
import ru.adsrv.frpjump.ui.devices.DevicesViewModel
import ru.adsrv.frpjump.ui.enroll.EnrollScreen
import ru.adsrv.frpjump.ui.enroll.EnrollViewModel
import ru.adsrv.frpjump.ui.settings.SettingsScreen
import ru.adsrv.frpjump.ui.settings.SettingsViewModel
import ru.adsrv.frpjump.ui.ssh.TerminalScreen
import ru.adsrv.frpjump.ui.web.WebScreen

private object Routes {
    const val ENROLL = "enroll"
    const val DEVICES = "devices"
    const val DEVICE = "device/{name}"
    const val SETTINGS = "settings"
    const val STORAGE = "storage"
    const val REMOVED = "removed"
    const val WEB = "web/{name}/{port}"
    const val TERMINAL = "terminal/{id}"
    fun terminal(id: Int) = "terminal/$id"
    fun device(name: String) = "device/${Uri.encode(name)}"
    fun web(name: String, localPort: Int) = "web/${Uri.encode(name)}/$localPort"
}

@Composable
fun AppNav() {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val nav = rememberNavController()
    val start = remember { routeFor(app.store.state()) }
    val removed by app.session.removed.collectAsStateWithLifecycle()
    // The server rejected this phone's token: polling has stopped; say so wherever we are.
    LaunchedEffect(removed) {
        if (removed && nav.currentDestination?.route != Routes.REMOVED) nav.replaceAll(Routes.REMOVED)
    }
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.REMOVED) {
            var busy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            MessageScreen(
                title = "frp-jump",
                text = "This phone was removed from the server. Its tunnels are stopped.",
                action = "Enroll again",
                busy = busy,
                onAction = {
                    busy = true
                    // The wipe runs in the app scope: it finishes even if this screen goes away.
                    val wipe = app.appScope.async { app.forgetLocally(keepPorts = true) }
                    scope.launch {
                        wipe.await()
                        nav.replaceAll(Routes.ENROLL)
                    }
                },
            )
        }
        composable(Routes.STORAGE) {
            MessageScreen(
                title = "frp-jump",
                text = "This phone's stored credentials cannot be read right now. " +
                    "Nothing was deleted; try again in a moment.",
                action = "Retry",
                onAction = { routeFor(app.store.state()).takeIf { it != Routes.STORAGE }?.let(nav::replaceAll) },
            )
        }
        composable(Routes.ENROLL) {
            EnrollScreen(vm = viewModel { EnrollViewModel(app) }, onEnrolled = { nav.replaceAll(Routes.DEVICES) })
        }
        composable(Routes.DEVICES) {
            DevicesScreen(
                vm = viewModel { DevicesViewModel(app) },
                onOpen = { nav.navigate(Routes.device(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onTerminals = { nav.navigate(Routes.terminal(it)) },
            )
        }
        composable(Routes.DEVICE) { entry ->
            val name = entry.arguments?.getString("name").orEmpty()
            DeviceCardScreen(
                vm = viewModel { DeviceCardViewModel(app, name) },
                onBack = { nav.popBackStack() },
                onOpenWeb = { nav.navigate(Routes.web(name, it)) },
                onOpenTerminal = { nav.navigate(Routes.terminal(it)) },
            )
        }
        composable(Routes.TERMINAL) { entry ->
            TerminalScreen(
                sessions = app.ssh,
                initialId = entry.arguments?.getString("id")?.toIntOrNull() ?: 0,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.WEB) { entry ->
            WebScreen(
                device = entry.arguments?.getString("name").orEmpty(),
                localPort = entry.arguments?.getString("port")?.toIntOrNull() ?: 0,
                onBack = { nav.popBackStack() },
            )
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

private fun routeFor(stored: StoredEnrollment) = when (stored) {
    is StoredEnrollment.Enrolled -> Routes.DEVICES
    StoredEnrollment.NotEnrolled -> Routes.ENROLL
    is StoredEnrollment.Unavailable -> Routes.STORAGE
}

/** Go to [route] with nothing to go back to. */
private fun NavHostController.replaceAll(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
}
