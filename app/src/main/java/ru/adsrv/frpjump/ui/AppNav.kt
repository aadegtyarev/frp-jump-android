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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.store.StoredEnrollment
import ru.adsrv.frpjump.ui.devices.DeviceCardScreen
import ru.adsrv.frpjump.ui.devices.DeviceCardViewModel
import ru.adsrv.frpjump.ui.devices.DevicesScreen
import ru.adsrv.frpjump.ui.devices.DevicesViewModel
import ru.adsrv.frpjump.ui.enroll.EnrollScreen
import ru.adsrv.frpjump.ui.enroll.EnrollViewModel
import ru.adsrv.frpjump.ui.profiles.ProfileEditScreen
import ru.adsrv.frpjump.ui.profiles.ProfileEditViewModel
import ru.adsrv.frpjump.ui.profiles.ProfilesSheet
import ru.adsrv.frpjump.ui.profiles.ProfilesViewModel
import ru.adsrv.frpjump.ui.settings.SettingsScreen
import ru.adsrv.frpjump.ui.settings.SettingsViewModel
import ru.adsrv.frpjump.ui.ssh.TerminalScreen
import ru.adsrv.frpjump.ui.web.WebScreen

private object Routes {
    const val LOADING = "loading"
    const val ENROLL = "enroll"
    const val ADD_PROFILE = "profiles/add"
    const val PROFILE = "profile/{id}"
    const val DEVICES = "devices"
    const val DEVICE = "device/{name}"
    const val SETTINGS = "settings"
    const val STORAGE = "storage"
    const val REMOVED = "removed"
    const val WEB = "web/{profile}/{name}/{port}"
    const val TERMINAL = "terminal/{id}"
    fun terminal(id: Int) = "terminal/$id"
    fun profile(id: String) = "profile/$id"
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
            val profilesVm = viewModel { ProfilesViewModel(app) }
            val profiles by profilesVm.items.collectAsStateWithLifecycle()
            var sheet by rememberSaveable { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            // E.g. another profile forgotten from the list over this screen: said here, not later elsewhere.
            val pendingNotice by app.session.notice.collectAsStateWithLifecycle()
            var notice by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(pendingNotice) {
                if (pendingNotice != null) app.session.takeAnyNotice()?.let { notice = it }
            }
            MessageScreen(
                title = stringResource(R.string.app_name),
                notice = notice,
                text = stringResource(R.string.removed_text, profiles.firstOrNull { it.current }?.name.orEmpty()),
                action = stringResource(R.string.enroll_again),
                busy = busy,
                error = error,
                secondary = stringResource(R.string.other_profiles).takeIf { profiles.size > 1 },
                onSecondary = { sheet = true },
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
                                error = app.getString(R.string.enroll_again_failed, it.userMessage())
                            }
                    }
                },
            )
            if (sheet) ProfileListSheet(profilesVm, nav, app, onDismiss = { sheet = false })
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
            val profilesVm = viewModel { ProfilesViewModel(app) }
            val profiles by profilesVm.items.collectAsStateWithLifecycle()
            var sheet by rememberSaveable { mutableStateOf(false) }
            EnrollScreen(
                vm = viewModel { EnrollViewModel(app, takesNotices = true) },
                onEnrolled = { nav.replaceAll(Routes.DEVICES) },
                onBack = null,
                onOtherProfiles = { sheet = true }.takeIf { profiles.size > 1 },
            )
            if (sheet) ProfileListSheet(profilesVm, nav, app, onDismiss = { sheet = false })
        }
        composable(Routes.ADD_PROFILE) {
            EnrollScreen(
                // Over the device list, which shows the notices.
                vm = viewModel { EnrollViewModel(app, takesNotices = false) },
                onEnrolled = { nav.replaceAll(Routes.DEVICES) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PROFILE) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ProfileEditScreen(vm = viewModel { ProfileEditViewModel(app, id) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.DEVICES) {
            val profilesVm = viewModel { ProfilesViewModel(app) }
            val profiles by profilesVm.items.collectAsStateWithLifecycle()
            var sheet by rememberSaveable { mutableStateOf(false) }
            DevicesScreen(
                vm = viewModel { DevicesViewModel(app) },
                profiles = profiles,
                onProfiles = { sheet = true },
                onOpen = { nav.navigate(Routes.device(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onTerminals = { nav.navigate(Routes.terminal(it)) },
            )
            if (sheet) ProfileListSheet(profilesVm, nav, app, onDismiss = { sheet = false })
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
            val profilesVm = viewModel { ProfilesViewModel(app) }
            val profiles by profilesVm.items.collectAsStateWithLifecycle()
            val forget by profilesVm.forget.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            SettingsScreen(
                vm = viewModel { SettingsViewModel(app) },
                profile = profiles.firstOrNull { it.current },
                forget = forget,
                onEditProfile = { nav.navigate(Routes.profile(it)) },
                // The next profile, if any; else enroll.
                onForget = { id -> profilesVm.forget(id) { scope.launch { nav.replaceAll(routeFor(app)) } } },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/**
 * The profile list over the current screen. After a switch or a forget
 * the app goes where the new current profile says, unless it is there
 * already: the device list follows the current profile by itself.
 */
@Composable
private fun ProfileListSheet(vm: ProfilesViewModel, nav: NavHostController, app: FrpJumpApp, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val reroute: () -> Unit = {
        scope.launch {
            val route = routeFor(app)
            if (nav.currentDestination?.route != route) nav.replaceAll(route)
        }
    }
    ProfilesSheet(
        vm = vm,
        onDismiss = onDismiss,
        onSwitched = reroute,
        onAdd = { nav.navigate(Routes.ADD_PROFILE) },
        onEdit = { nav.navigate(Routes.profile(it)) },
        onForgotten = reroute,
    )
}

/**
 * Where the app opens: the current profile's devices, enrollment without
 * one, the "removed" screen when its server deleted it. Waits for the
 * profiles to load.
 */
private suspend fun routeFor(app: FrpJumpApp): String = withContext(Dispatchers.IO) {
    if (!app.profiles.load()) return@withContext Routes.STORAGE
    if (app.profiles.current?.session?.removed?.value == true) return@withContext Routes.REMOVED
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
