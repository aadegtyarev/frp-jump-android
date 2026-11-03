package ru.adsrv.frpjump.mqtt

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ssh.MemoryStorage

/** Accepted fake broker links on the real production scheduler, no sockets or real credentials. */
class MqttLoginPersistenceTest {
    @Test fun `keystore seal failure cannot strand an accepted login in Connecting`() = accepted(Fault.SEAL)
    @Test fun `remember write failure cannot strand an accepted login in Connecting`() = accepted(Fault.REMEMBER)
    @Test fun `forget write failure cannot strand an accepted login in Connecting`() = accepted(Fault.FORGET)
    @Test fun `successful persistence opens and subscribes on the production scheduler`() = accepted(null)
    @Test fun `MQTT 3 fallback keeps accepted login usable when persistence fails`() = accepted(Fault.REMEMBER, MqttVersion.V3_1_1)

    private fun accepted(fault: Fault?, version: MqttVersion = MqttVersion.V5) {
        val scheduler = ExecutorScheduler()
        val storage = FaultStorage()
        var failSeal = false
        val box = object : SecretBox {
            override fun seal(plain: String): String {
                if (failSeal) throw IllegalStateException("Synthetic locked key")
                return "synthetic:" + plain.reversed()
            }
            override fun open(sealed: String) = sealed.removePrefix("synthetic:").reversed()
            override fun destroy() = Unit
        }
        val logins = MqttLogins(storage, box)
        if (fault == Fault.FORGET) logins.remember("p", "c", MqttLogin("old", "synthetic-old"))
        val subscribed = CopyOnWriteArrayList<String>()
        val closed = AtomicInteger()
        var requests = 0
        var refusedLogin = false
        var rejectNextLogin = false
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) { subscribed += filter; done(true) }
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) { done(PublishAnswer.Accepted) }
            override fun close() { closed.incrementAndGet() }
        }
        val connector = object : MqttConnector {
            override fun connect(request: ConnectRequest, events: LinkEvents, done: (ConnectOutcome) -> Unit) {
                requests++
                done(when {
                    version == MqttVersion.V3_1_1 && request.version == MqttVersion.V5 -> ConnectOutcome.Refused(ConnectRefusal.UNSUPPORTED_VERSION)
                    !refusedLogin || rejectNextLogin -> {
                        refusedLogin = true
                        ConnectOutcome.Refused(ConnectRefusal.BAD_LOGIN)
                    }
                    else -> ConnectOutcome.Opened(link)
                })
            }
        }
        val session = MqttSession(1, "p", "c", "synthetic", 1883, 12345,
            MqttEnvironment(logins, PinStore(MemoryStorage()), connector, scheduler))
        fun drain() { repeat(4) { scheduler.runAfterPending {} } }
        var oldIssue: MqttLoginStorageFailure? = null
        try {
            session.connect()
            drain()
            assertTrue(session.state.value is MqttState.NeedsLogin)
            failSeal = fault == Fault.SEAL
            storage.failWrite = fault == Fault.REMEMBER || fault == Fault.FORGET
            session.login("typed", "synthetic-typed", remember = fault != Fault.FORGET)
            drain()
            assertEquals("Broker accepted; local persistence must not prevent Open", MqttState.Open(version), session.state.value)
            assertTrue("Snapshot must be subscribed after accepted login", "#" in subscribed)
            assertEquals("Accepted transport must remain alive", 0, closed.get())
            failSeal = false
            storage.failWrite = false
            if (fault == Fault.FORGET) assertEquals("old", logins.get("p", "c")?.user)
            else if (fault == null) assertEquals("typed", logins.get("p", "c")?.user)
            else assertNull(logins.get("p", "c"))
            val issue = session.loginStorageFailure.value
            if (fault == null) assertNull(issue) else {
                oldIssue = checkNotNull(issue)
                assertEquals(if (fault == Fault.FORGET) MqttLoginStorageAction.FORGET else MqttLoginStorageAction.REMEMBER, issue.action)
                assertFalse(issue.detail.contains("synthetic-typed"))
                val connectionsBefore = requests
                val subscriptionsBefore = subscribed.toList()
                session.retryLoginStorage(issue)
                drain()
                assertNull(session.loginStorageFailure.value)
                assertEquals(connectionsBefore, requests)
                assertEquals(subscriptionsBefore, subscribed.toList())
                assertEquals(0, closed.get())
                if (fault == Fault.FORGET) assertNull(logins.get("p", "c"))
                else assertEquals("typed", logins.get("p", "c")?.user)
                val writes = storage.writes.get()
                session.retryLoginStorage(issue)
                drain()
                assertEquals("A cleared warning is not a new storage request", writes, storage.writes.get())
                if (fault == Fault.REMEMBER) {
                    storage.failWrite = true
                    session.login("replacement", "synthetic-replacement", remember = true)
                    drain()
                    val newer = checkNotNull(session.loginStorageFailure.value)
                    assertNotSame(issue, newer)
                    val writesBefore = storage.writes.get()
                    session.retryLoginStorage(issue)
                    drain()
                    assertEquals(writesBefore, storage.writes.get())
                    storage.failWrite = false
                    session.retryLoginStorage(newer)
                    drain()
                    assertEquals("replacement", logins.get("p", "c")?.user)
                }
                if (fault == Fault.SEAL) {
                    rejectNextLogin = true
                    val writesBefore = storage.writes.get()
                    session.login("rejected", "synthetic-rejected", remember = true)
                    drain()
                    assertTrue(session.state.value is MqttState.NeedsLogin)
                    assertNull(session.loginStorageFailure.value)
                    session.retryLoginStorage(issue)
                    drain()
                    assertEquals("Rejected broker credentials must not be persisted", writesBefore, storage.writes.get())
                }
            }
        } finally {
            session.close()
            drain()
            assertEquals(MqttState.Ended, session.state.value)
            val writes = storage.writes.get()
            oldIssue?.let { session.retryLoginStorage(it) }
            drain()
            assertEquals("Ended sessions cannot retry an old storage choice", writes, storage.writes.get())
        }
    }

    @Test
    fun `unreadable saved map cannot be claimed forgotten after anonymous fallback`() {
        val storage = FaultStorage()
        MqttLogins(storage, box()).remember("p", "c", MqttLogin("old", "synthetic"))
        val before = storage.value
        storage.failRead = true
        val logins = MqttLogins(storage, box())
        assertNull("Reading for connection may fall back to anonymous", logins.get("p", "c"))
        assertTrue("Forget must report unreadable storage, not silently claim success", runCatching { logins.forget("p", "c") }.isFailure)
        assertEquals(before, storage.value)
        storage.failRead = false
        assertEquals("old", logins.get("p", "c")?.user)
        logins.forget("p", "c")
        assertNull(logins.get("p", "c"))
    }

    @Test
    fun `remember cannot overwrite other saved logins when reading their map fails`() {
        val storage = FaultStorage()
        val seed = MqttLogins(storage, box())
        seed.remember("p", "c", MqttLogin("old", "synthetic"))
        seed.remember("other", "d", MqttLogin("other", "synthetic-other"))
        val before = storage.value
        storage.failRead = true
        assertTrue("Unreadable map must not be replaced by one fresh entry", runCatching {
            MqttLogins(storage, box()).remember("new", "e", MqttLogin("typed", "synthetic-typed"))
        }.isFailure)
        assertEquals(before, storage.value)
    }

    @Test
    fun `automatic reconnect keeps accepted memory login without repeating failed storage`() {
        val clock = FakeScheduler()
        val storage = FaultStorage()
        val logins = MqttLogins(storage, box())
        var events: LinkEvents? = null
        val requests = mutableListOf<ConnectRequest>()
        val link = object : MqttLink {
            override fun subscribe(filter: String, done: (Boolean) -> Unit) { done(true) }
            override fun unsubscribe(filter: String) = Unit
            override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit) = Unit
            override fun close() = Unit
        }
        val connector = object : MqttConnector {
            override fun connect(request: ConnectRequest, callback: LinkEvents, done: (ConnectOutcome) -> Unit) {
                requests += request
                events = callback
                done(if (requests.size == 1) ConnectOutcome.Refused(ConnectRefusal.BAD_LOGIN) else ConnectOutcome.Opened(link))
            }
        }
        val session = MqttSession(1, "p", "c", "synthetic", 1883, 12345,
            MqttEnvironment(logins, PinStore(MemoryStorage()), connector, clock))
        try {
            session.connect()
            storage.failWrite = true
            session.login("typed", "synthetic-typed", remember = true)
            val issue = checkNotNull(session.loginStorageFailure.value)
            assertEquals(1, storage.writes.get())
            events!!.lost()
            assertEquals(MqttState.Reconnecting, session.state.value)
            clock.advanceBy(1_000)
            assertEquals(MqttState.Open(MqttVersion.V5), session.state.value)
            assertEquals(3, requests.size)
            assertSame("Reconnect reuses the accepted in-memory login object", requests[1].login, requests.last().login)
            assertEquals("typed", requests.last().login?.user)
            assertEquals("synthetic-typed", requests.last().login?.password)
            assertEquals("Reconnect must not become a repeated persistence attempt", 1, storage.writes.get())
            assertSame(issue, session.loginStorageFailure.value)
            storage.failWrite = false
            session.retryLoginStorage(issue)
            assertNull(session.loginStorageFailure.value)
            assertEquals("typed", logins.get("p", "c")?.user)
        } finally {
            session.close()
        }
    }

    private fun box() = object : SecretBox {
        override fun seal(plain: String) = "synthetic:" + plain.reversed()
        override fun open(sealed: String) = sealed.removePrefix("synthetic:").reversed()
        override fun destroy() = Unit
    }

    private enum class Fault { SEAL, REMEMBER, FORGET }
    private class FaultStorage : TextStorage {
        @Volatile var value: String? = null
        @Volatile var failWrite = false
        @Volatile var failRead = false
        val writes = AtomicInteger()
        override fun read(): String? {
            if (failRead) throw IOException("Synthetic read failure")
            return value
        }
        override fun write(text: String) {
            writes.incrementAndGet()
            if (failWrite) throw IOException("Synthetic write failure")
            value = text
        }
        override fun delete() { value = null }
    }
}
