package ru.adsrv.frpjump.profiles

import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.*
import ru.adsrv.frpjump.connections.*
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.devices.*
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.tunnel.*
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.ui.connections.connectionsUiState
import ru.adsrv.frpjump.ui.devices.devicesUiState

/** Real owners over memory files and intercepted synthetic HTTP; never activated or polled. */
class SyntheticProfileInformation : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val files = ConcurrentHashMap<String, MemoryStorage>()
    val deviceRequests = AtomicInteger()
    val versionRequests = AtomicInteger()
    @Volatile var fail = false
    @Volatile var beforeResponse: (() -> Unit)? = null
    @Volatile var onEnqueue: ((Call) -> Unit)? = null
    val enqueues = java.util.concurrent.CopyOnWriteArrayList<String>()
    val api: FrpJumpApi
    private val http = OkHttpClient.Builder().dispatcher(Dispatcher(ApiCallbackExitExecutor()))
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                enqueues += "${call.request().url.host}/${call.request().url.pathSegments.last()}"
                onEnqueue?.invoke(call)
            }
        }).addInterceptor { chain ->
        val request = chain.request()
        val version = request.url.encodedPath.endsWith("/version")
        if (version) versionRequests.incrementAndGet() else {
            check(request.url.encodedPath.endsWith("/devices")) { "No tunnel/control request allowed" }
            check(request.header("Authorization") == "Bearer secret-token")
            deviceRequests.incrementAndGet()
        }
        beforeResponse?.invoke()
        val body = if (fail) """{"detail":"private-server-secret"}""" else if (version)
            """{"protocol_version":1,"package_version":"synthetic","features":["device-display"]}"""
        else """[{"name":"same","enrolled_at":"2026-01-01","last_seen_at":null,"enabled":true},{"name":"no-connections","enrolled_at":"2026-01-01","last_seen_at":null,"enabled":true}]"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fail) 503 else 200).message("synthetic")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private val record = RestoreRecord(MemoryStorage()).apply { load() }
    init { api = FrpJumpApi(http) }
    private val shared = ProfileShared(api, LocalPortStore(MemoryStorage()), MutableStateFlow(AppSettings()),
        MutableStateFlow(false), File("synthetic-no-frpc"), File("synthetic-no-frpc-root"), { error("No service") }, scope,
        { file -> files.getOrPut(file.path) { MemoryStorage() } }, DirectKeptStore(MemoryStorage()), MutableStateFlow(null), record)
    val profiles = listOf(Profile("work", "Work", "green", "secret-alias"), Profile("home", "Home", "blue", "secret-alias-2"))
    val runtimes = profiles.associate { p ->
        val store = CredentialStore(MemoryStorage(), FakeKeystore().box(p.keyAlias))
        store.save(credentials("${p.id}.example", token = "secret-token").copy(certPem = "secret-cert", keyPem = "secret-key", caCertPem = "secret-ca"))
        p.id to ProfileRuntime(p.id, store, File("synthetic/${p.id}"), MutableStateFlow("work"), shared)
    }
    val state = MutableStateFlow(ProfilesState(loaded = true, index = ProfileIndex(profiles, "work"), runtimes = runtimes))
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    val information = ProfileInformation(state, hidden)
    override fun close() { runtimes.values.forEach { it.close() }; scope.cancel(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
}

class ProfileInformationTest {
    @Test fun `one ordinary refresh includes all profiles and full devices without changing wanted or runtime lifetime`() = runBlocking {
        SyntheticProfileInformation().use { f ->
            val initial = f.information.observe().first()
            assertTrue(initial.loaded); assertTrue(initial.profiles.all { it.devices?.devices == null })
            assertEquals(0, f.deviceRequests.get()); assertEquals(0, f.versionRequests.get())
            f.runtimes.getValue("work").connections.save(Connection("ssh", "same", 22, PortKind.SSH))
            f.information.refreshAll()
            assertEquals(2, f.deviceRequests.get()); assertEquals(2, f.versionRequests.get())
            val known = f.information.observe().first()
            assertEquals(listOf("work", "home"), known.profiles.map { it.id })
            assertTrue(known.profiles.all { it.devices!!.devices!!.map { d -> d.name } == listOf("same", "no-connections") })
            assertEquals(1, known.profiles[0].rows.size); assertTrue(known.profiles[1].rows.isEmpty())
            f.runtimes.getValue("work").sshLogins.remember("same", 22, ru.adsrv.frpjump.ssh.SshLogin(method = ru.adsrv.frpjump.ssh.SshAuthMethod.PASSWORD))
            assertEquals(ru.adsrv.frpjump.ssh.SshAuthMethod.PASSWORD, connectionsUiState(f.information.observe().first()).sshMethods["work/ssh"])
            f.hidden.value = setOf(PendingUndo.connectionKey("work", "ssh"))
            assertTrue(f.information.observe().first().profiles[0].rows.isEmpty())
            f.hidden.value = emptySet()
            assertEquals("ssh", f.information.observe().first().profiles[0].rows.single().connection.id)
            assertTrue(f.runtimes.values.none { it.devices.wanted })
            assertTrue(f.files.keys.any { it.endsWith(ProfileFiles.DEVICE_LIST) })
            f.fail = true
            f.information.refreshAll()
            val stale = f.information.observe().first()
            assertEquals(4, f.deviceRequests.get()); assertEquals(4, f.versionRequests.get())
            assertTrue(stale.profiles.all { it.devices!!.stale && it.devices.error == "private-server-secret" && it.devices.devices!!.size == 2 })
            f.state.value = f.state.value.copy(index = ProfileIndex(f.profiles + Profile("pending", "Pending", "grey", "secret", true), "home"))
            val unavailable = f.information.observe().first()
            assertEquals("home", unavailable.current)
            assertFalse(unavailable.profiles.last().available); assertNull(unavailable.profiles.last().devices)
            assertTrue(unavailable.profiles.last().activationPending)
            f.state.value = ProfilesState(failure = "private-load-error", retrying = true)
            val failed = f.information.observe().first()
            assertFalse(failed.loaded); assertTrue(failed.retrying); assertEquals("private-load-error", failed.failure)
        }
    }

    @Test fun `both UI projections share Undo saved unnamed and repeated-name rules while preserving their presentation`() {
        val p = Profile("work", "Work", "green", "secret")
        val saved = listOf(Connection("one", "same", 22, PortKind.SSH), Connection("two", "same", 80, PortKind.WEB))
        val tunnels = TunnelsState(listOf(Tunnel("same", 22, "secret-grant", 40000, VisitorState(TunnelStatus.DIRECT)),
            Tunnel("other", 502, "secret-grant-2", 40001, VisitorState(TunnelStatus.RELAYED))))
        val devices = DeviceList(listOf(DeviceRow("no-connections", true, null, false), DeviceRow("same", false, null, false),
            DeviceRow("other", false, null, false)), Instant.EPOCH, stale = true, error = "private-error", unreachable = true)
        val part = profileInformationPart(p, saved, tunnels, emptySet(), devices)
        val home = profileInformationPart(p.copy(id = "home", name = "Home"), saved, TunnelsState(), emptySet(), DeviceList())
        val information = ProfileInformationState(true, current = "work", profiles = listOf(part, home))
        assertEquals(listOf("one", "two", "unsaved:other:502"), part.rows.map { it.connection.id })
        val connections = connectionsUiState(information)
        assertEquals(listOf("other", "same", "same"), connections.groups.map { it.name })
        assertEquals(listOf("one", "two"), connections.groups.first { it.profile == "work" && it.name == "same" }.rows.map { it.connection.id })
        assertEquals(mapOf("work" to "green", "home" to "green"), connections.colors)
        assertTrue(connections.manyProfiles)
        val ui = devicesUiState(information, true)
        assertEquals(listOf("same", "other", "no-connections"), ui.devices!!.map { it.name })
        assertEquals(mapOf("same" to listOf(22), "other" to listOf(502)), ui.tunnelPorts)
        assertEquals("private-error", ui.error); assertEquals(Instant.EPOCH, ui.staleSince); assertTrue(ui.refreshing); assertTrue(ui.unreachable)
        assertNull(devicesUiState(information.copy(current = "home"), false).devices)
        val hidden = setOf(PendingUndo.connectionKey("work", "one"), PendingUndo.tunnelKey("work", "other", 502))
        val undone = profileInformationPart(p, saved, tunnels, hidden, devices)
        assertEquals(listOf("two"), undone.rows.map { it.connection.id })
        assertTrue(undone.tunnels!!.tunnels.any { it.device == "same" }) // Deleted row doesn't hide other device ports by itself.
        assertEquals(part, profileInformationPart(p, saved, tunnels, emptySet(), devices))
    }
}
