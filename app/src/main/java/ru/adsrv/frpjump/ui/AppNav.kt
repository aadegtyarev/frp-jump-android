package ru.adsrv.frpjump.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import kotlinx.coroutines.flow.first
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
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.store.StoredEnrollment
import ru.adsrv.frpjump.ui.connections.ConnectionsScreen
import ru.adsrv.frpjump.ui.connections.ConnectionsViewModel
import ru.adsrv.frpjump.ui.connections.TerminalsScreen
import ru.adsrv.frpjump.ui.devices.DeviceCardScreen
import ru.adsrv.frpjump.ui.devices.DeviceCardViewModel
import ru.adsrv.frpjump.ui.devices.DevicesScreen
import ru.adsrv.frpjump.ui.devices.DevicesViewModel
import ru.adsrv.frpjump.ui.enroll.EnrollScreen
import ru.adsrv.frpjump.ui.files.FilesScreen
import ru.adsrv.frpjump.ui.files.FilesViewModel
import ru.adsrv.frpjump.files.FilesTarget
import ru.adsrv.frpjump.ui.enroll.EnrollViewModel
import ru.adsrv.frpjump.ui.profiles.ProfileEditScreen
import ru.adsrv.frpjump.ui.profiles.ProfileEditViewModel
import ru.adsrv.frpjump.ui.profiles.ProfilesSheet
import ru.adsrv.frpjump.ui.profiles.ProfilesViewModel
import ru.adsrv.frpjump.ui.settings.SettingsScreen
import ru.adsrv.frpjump.ui.settings.SettingsViewModel
import ru.adsrv.frpjump.ui.ssh.KeyLoginFlow
import ru.adsrv.frpjump.ui.ssh.TerminalScreen
import ru.adsrv.frpjump.ssh.KeyInstallTarget
import ru.adsrv.frpjump.ssh.KeyInstaller
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ui.web.WebScreen

private object Routes {
    const val LOADING = "loading"
    const val ENROLL = "enroll"
    const val ADD_PROFILE = "profiles/add"
    const val PROFILE = "profile/{id}"
    const val DEVICES = "devices"
    const val CONNECTIONS = "connections"
    const val TERMINALS = "terminals"
    const val DEVICE = "device/{name}"
    const val SETTINGS = "settings"
    const val STORAGE = "storage"
    const val REMOVED = "removed"
    const val WEB = "web/{profile}/{name}/{port}/{target}/{https}"
    const val TERMINAL = "terminal/{id}"
    const val FILES = "files/{id}"
    fun files(id: Int) = "files/$id"
    fun terminal(id: Int) = "terminal/$id"
    fun profile(id: String) = "profile/$id"
    fun device(name: String) = "device/${Uri.encode(name)}"
    fun web(profile: String, name: String, localPort: Int, target: Int, https: Boolean) =
        "web/$profile/${Uri.encode(name)}/$localPort/$target/$https"

    fun of(tab: Tab) = when (tab) {
        Tab.DEVICES -> DEVICES
        Tab.CONNECTIONS -> CONNECTIONS
        Tab.TERMINALS -> TERMINALS
        Tab.SETTINGS -> SETTINGS
    }
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
    // A notification's tap: that profile's device card, over whatever is shown.
    val openDevice by app.openDevice.collectAsStateWithLifecycle()
    LaunchedEffect(openDevice) {
        val request = openDevice ?: return@LaunchedEffect
        val route = nav.currentBackStackEntryFlow.map { it.destination.route }.first { it != Routes.LOADING }
        app.openDevice.value = null
        if (route in setOf(Routes.ENROLL, Routes.REMOVED, Routes.STORAGE) || app.profiles.runtime(request.profile) == null) return@LaunchedEffect
        app.profiles.setCurrent(request.profile)
        nav.navigate(Routes.device(request.device)) { popUpTo(Routes.DEVICE) { inclusive = true } }
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
                                error = Texts.get(R.string.enroll_again_failed, it.userMessage())
                            }
                    }
                },
            )
            if (sheet) ProfileListSheet(profilesVm, nav, app, onDismiss = { sheet = false })
        }
        composable(Routes.STORAGE) {
            val scope = rememberCoroutineScope()
            MessageScreen(
                title = stringResource(R.string.app_name),
                text = stringResource(R.string.storage_unreadable),
                action = stringResource(R.string.retry),
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
            TabFrame(Tab.DEVICES, nav) {
                DevicesScreen(
                    vm = viewModel { DevicesViewModel(app) },
                    profiles = profiles,
                    onProfiles = { sheet = true },
                    onOpen = { nav.navigate(Routes.device(it)) },
                )
            }
            if (sheet) ProfileListSheet(profilesVm, nav, app, onDismiss = { sheet = false })
        }
        composable(Routes.CONNECTIONS) {
            TabFrame(Tab.CONNECTIONS, nav) {
                ConnectionsScreen(
                    vm = viewModel { ConnectionsViewModel(app) },
                    onOpenWeb = { nav.navigate(webRoute(it)) },
                    onOpenTerminal = { nav.navigate(Routes.terminal(it)) },
                    onOpenFiles = { nav.navigate(Routes.files(it)) },
                )
            }
        }
        composable(Routes.TERMINALS) {
            val profilesState by app.profiles.state.collectAsStateWithLifecycle()
            TabFrame(Tab.TERMINALS, nav) {
                TerminalsScreen(app.ssh, profilesState.index.profiles.associate { it.id to it.color }, onOpen = { nav.navigate(Routes.terminal(it)) })
            }
        }
        composable(Routes.DEVICE) { entry ->
            val name = entry.arguments?.getString("name").orEmpty()
            val vm = viewModel { DeviceCardViewModel(app, name) }
            DeviceCardScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenWeb = { nav.navigate(webRoute(it)) },
                onOpenTerminal = { nav.navigate(Routes.terminal(it)) },
                onOpenFiles = { nav.navigate(Routes.files(it)) },
            )
        }
        composable(Routes.TERMINAL) { entry ->
            val profilesState by app.profiles.state.collectAsStateWithLifecycle()
            TerminalScreen(
                sessions = app.ssh,
                snippetStore = app.snippets,
                profileColors = profilesState.index.profiles.associate { it.id to it.color },
                initialId = entry.arguments?.getString("id")?.toIntOrNull() ?: 0,
                onBack = { nav.popBackStack() },
                onOpenFiles = { s ->
                    nav.navigate(Routes.files(app.fileTargets.put(FilesTarget(s.profile, s.device, s.port, s.user, s.auth))))
                },
                rememberLogin = { s, login ->
                    // Only the dialog's next start: a write that fails changes nothing else.
                    app.appScope.launch(Dispatchers.IO) { runCatching { app.profiles.runtime(s.profile)?.sshLogins?.remember(s.device, s.port, login) } }
                },
                keyLogin = remember { KeyLoginFlow(KeyInstaller(app.sshEnv, app.ssh), app.appScope) },
                keyLoginTarget = { s ->
                    // Offered while this login is by password: the session's own, and the one remembered for its port.
                    app.profiles.runtime(s.profile)
                        ?.takeIf { s.auth is SshAuth.Password && it.sshLogins.get(s.device, s.port).method == SshAuthMethod.PASSWORD }
                        ?.let { r -> KeyInstallTarget(s.profile, r.hostKeys, s.device, s.port, s.user, r.sshLogins) { s.localPort } }
                },
            )
        }
        composable(Routes.FILES) { entry ->
            val id = entry.arguments?.getString("id")?.toIntOrNull() ?: 0
            FilesScreen(vm = viewModel { FilesViewModel(app, id) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.WEB) { entry ->
            WebScreen(
                profile = entry.arguments?.getString("profile").orEmpty(),
                device = entry.arguments?.getString("name").orEmpty(),
                localPort = entry.arguments?.getString("port")?.toIntOrNull() ?: 0,
                targetPort = entry.arguments?.getString("target")?.toIntOrNull() ?: 0,
                https = entry.arguments?.getString("https") == "true",
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            val profilesVm = viewModel { ProfilesViewModel(app) }
            val profiles by profilesVm.items.collectAsStateWithLifecycle()
            val forget by profilesVm.forget.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            TabFrame(Tab.SETTINGS, nav) {
                SettingsScreen(
                    vm = viewModel { SettingsViewModel(app) },
                    profile = profiles.firstOrNull { it.current },
                    forget = forget,
                    onEditProfile = { nav.navigate(Routes.profile(it)) },
                    // The next profile, if any; else enroll.
                    onForget = { id -> profilesVm.forget(id) { scope.launch { nav.replaceAll(routeFor(app)) } } },
                    onBack = null,
                )
            }
        }
    }
}

/** A tab's screen above the bottom navigation; the screen keeps its own status bar padding. */
@Composable
private fun TabFrame(selected: Tab, nav: NavHostController, content: @Composable () -> Unit) {
    val snackbars = remember { SnackbarHostState() }
    UpdateAnnouncement(selected, snackbars) { nav.goToTab(Tab.SETTINGS) }
    Scaffold(
        bottomBar = { BottomNav(selected) { if (it != selected) nav.goToTab(it) } },
        snackbarHost = { SnackbarHost(snackbars) },
        containerColor = AppTheme.colors.ground,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) { content() }
    }
}

/**
 * A newer release, once per version: a snackbar whose "Show" leads to
 * Settings, where the version row offers it. On Settings itself the row
 * says it already.
 */
@Composable
private fun UpdateAnnouncement(selected: Tab, snackbars: SnackbarHostState, onShow: () -> Unit) {
    val updater = (LocalContext.current.applicationContext as FrpJumpApp).updater
    val update by updater.state.collectAsStateWithLifecycle()
    // Taken from the updater at once (told once, whether or not the owner looks), then shown.
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(update.announce) {
        val version = update.announce ?: return@LaunchedEffect
        updater.announced()
        if (selected != Tab.SETTINGS) shown = version
    }
    val text = shown?.let { stringResource(R.string.update_snackbar, it) }
    val action = stringResource(R.string.update_snackbar_show)
    LaunchedEffect(text) {
        if (text == null) return@LaunchedEffect
        if (snackbars.showSnackbar(text, actionLabel = action, withDismissAction = true) == SnackbarResult.ActionPerformed) onShow()
        shown = null
    }
}

/** Switches tabs: one entry per tab on top of Devices, each keeping its state; Back leads to Devices. */
private fun NavHostController.goToTab(tab: Tab) = navigate(Routes.of(tab)) {
    popUpTo(Routes.DEVICES) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** The web view of [row]'s tunnel. */
private fun webRoute(row: ConnectionRow): String {
    val c = row.connection
    return Routes.web(row.profile, c.device, row.tunnel?.localPort ?: 0, c.port, c.https)
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
