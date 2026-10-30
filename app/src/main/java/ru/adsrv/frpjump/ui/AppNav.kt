package ru.adsrv.frpjump.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    const val LOADING = "loading"
    const val ENROLL = "enroll"
    const val DEVICES = "devices"
    const val DEVICE = "device/{name}"
    const val SETTINGS = "settings"
    const val STORAGE = "storage"
    const val REMOVED = "removed"
    const val WEB = "web/{profile}/{name}/{port}"
    const val TERMINAL = "terminal/{id}"
    fun terminal(id: Int) = "terminal/$id"
    fun device(name: String) = "device/${Uri.encode(name)}"
    fun web(profile: String, name: String, localPort: Int) = "web/$profile/${Uri.encode(name)}/$localPort"
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun AppNav() {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val nav = rememberNavController()
    val removedFlow = remember {
        app.profiles.state.map { it.current }.distinctUntilChanged().flatMapLatest { it?.session?.removed ?: flowOf(false) }
    }
    val removed by removedFlow.collectAsStateWithLifecycle(false)
    // The server rejected the current profile's token: its polling has stopped; say so wherever we are.
    LaunchedEffect(removed) {
        if (removed && nav.currentDestination?.route != Routes.REMOVED) nav.replaceAll(Routes.REMOVED)
    }
    NavHost(navController = nav, startDestination = Routes.LOADING) {
        composable(Routes.LOADING) {
            // Profiles load (and v1 data is upgraded) off the main thread; then go where they say.
            LaunchedEffect(Unit) { nav.replaceAll(routeFor(app)) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        composable(Routes.REMOVED) {
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            MessageScreen(
                title = "frp-jump",
                text = "This phone was removed from the server. Its tunnels are stopped.",
                action = "Enroll again",
                busy = busy,
                error = error,
                onAction = {
                    busy = true
                    error = null
                    // The wipe runs in the app scope: it finishes even if this screen goes away.
                    val wipe = app.appScope.async {
                        runCatching { app.profiles.current?.let { app.forgetProfile(it.id, keepData = true) } }
                    }
                    scope.launch {
                        wipe.await()
                            .onSuccess { nav.replaceAll(Routes.ENROLL) }
                            .onFailure {
                                busy = false
                                error = "Could not wipe the old credentials (${it.userMessage()}). Try again."
                            }
                    }
                },
            )
        }
        composable(Routes.STORAGE) {
            val scope = rememberCoroutineScope()
            MessageScreen(
                title = "frp-jump",
                text = "This phone's stored credentials cannot be read right now. " +
                    "Nothing was deleted; try again in a moment.",
                action = "Retry",
                onAction = { scope.launch { routeFor(app).takeIf { it != Routes.STORAGE }?.let(nav::replaceAll) } },
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
            val vm = viewModel { DeviceCardViewModel(app, name) }
            DeviceCardScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenWeb = { nav.navigate(Routes.web(vm.profile, name, it)) },
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
                profile = entry.arguments?.getString("profile").orEmpty(),
                device = entry.arguments?.getString("name").orEmpty(),
                localPort = entry.arguments?.getString("port")?.toIntOrNull() ?: 0,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            val scope = rememberCoroutineScope()
            SettingsScreen(
                vm = viewModel { SettingsViewModel(app) },
                onBack = { nav.popBackStack() },
                // The next profile, if any; else enroll.
                onForgotten = { scope.launch { nav.replaceAll(routeFor(app)) } },
            )
        }
    }
}

/** Where the app opens: the current profile's devices, enrollment without one. Waits for the profiles to load. */
private suspend fun routeFor(app: FrpJumpApp): String = withContext(Dispatchers.IO) {
    if (!app.profiles.load()) return@withContext Routes.STORAGE
    when (app.profiles.current?.store?.state() ?: StoredEnrollment.NotEnrolled) {
        is StoredEnrollment.Enrolled -> Routes.DEVICES
        StoredEnrollment.NotEnrolled -> Routes.ENROLL
        is StoredEnrollment.Unavailable -> Routes.STORAGE
    }
}

/** Go to [route] with nothing to go back to. */
private fun NavHostController.replaceAll(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
}
