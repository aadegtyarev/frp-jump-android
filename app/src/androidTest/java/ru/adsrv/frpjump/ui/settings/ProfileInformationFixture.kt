package ru.adsrv.frpjump.ui.settings

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import ru.adsrv.frpjump.api.*
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.direct.DirectKeptStore
import ru.adsrv.frpjump.ports.*
import ru.adsrv.frpjump.profiles.*
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.store.*
import ru.adsrv.frpjump.store.Credentials

/** Real information/runtime owners, memory files and intercepted HTTP only; never activated. */
internal class ProfileInformationFixture : AutoCloseable {
    private class Memory : TextStorage {
        var value: String? = null
        override fun read() = value
        override fun write(text: String) { value = text }
        override fun delete() { value = null }
    }
    private val box = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun destroy() = Unit
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var fail = false
    private val body = (0 until 3000).joinToString(",", "[", "]") { i ->
        """{"name":"board$i","enrolled_at":"2026-01-01","last_seen_at":null,"enabled":true,"label":"${if (i == 1500) "Щиток" else "Other"}"}"""
    }
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.host.endsWith(".example"))
        val version = request.url.encodedPath.endsWith("/version")
        check(version || request.url.encodedPath.endsWith("/devices"))
        val response = if (fail) """{"detail":"synthetic-private-error"}""" else if (version)
            """{"protocol_version":1,"package_version":"synthetic","features":["device-display"]}""" else body
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fail) 503 else 200).message("synthetic")
            .body(response.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private val files = java.util.concurrent.ConcurrentHashMap<String, Memory>()
    private val shared = ProfileShared(FrpJumpApi(http), LocalPortStore(Memory()), MutableStateFlow(AppSettings()),
        MutableStateFlow(false), File("synthetic-no-frpc"), File("synthetic-no-frpc-dir"), { error("No service") }, scope,
        { f -> files.getOrPut(f.path) { Memory() } }, DirectKeptStore(Memory()), MutableStateFlow(null), RestoreRecord(Memory()).apply { load() })
    private val profiles = listOf(Profile("work", "Work", "green", "synthetic"), Profile("home", "Home", "blue", "synthetic"))
    private val runtimes = profiles.associate { p ->
        val credentials = CredentialStore(Memory(), box).apply { save(Credentials("https://${p.id}.example", "phone", "phone", "synthetic-token", "synthetic-cert", "synthetic-key", "synthetic-ca", "relay.example", 7000)) }
        p.id to ProfileRuntime(p.id, credentials, File("synthetic-info/${p.id}"), MutableStateFlow<String?>("work"), shared).apply {
            val users = if (p.id == "work") listOf("root", "admin") else listOf("root")
            users.forEach { user -> connections.save(Connection("${p.id}-$user", "board1500", 22, PortKind.SSH, customName = "Service $user", user = user)) }
        }
    }
    /** The shared state flows, so a test can build other owners (the SSH targets) over the same profiles and connections. */
    val state = MutableStateFlow(ProfilesState(loaded = true, index = ProfileIndex(profiles, "work"), runtimes = runtimes))
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    val information = ProfileInformation(state, hidden)
    override fun close() {
        runtimes.values.forEach { it.close() }; scope.cancel()
        http.connectionPool.evictAll()
    }
}
