package ru.adsrv.frpjump.ssh

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.profiles.FakeKeystore
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Which of the app's keys a key login got in with, against a loopback Apache SSHD (synthetic keys): the imported key is offered first,
 * the phone's after it, and the one the device accepts is the answer. Nothing of it is stored.
 */
class UsedKeyTest {
    private lateinit var server: SshServer
    private lateinit var pin: PinnedHostKey

    /** The key blobs (base64) the device accepts. */
    private val accepted = mutableSetOf<String>()

    /** The blobs offered, in order. */
    private val offered = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val box = FakeKeystore().box("synthetic-ssh")
    private val phone = syntheticKey("frp-jump@test")
    private val keyStorage = MemoryStorage(
        buildJsonObject {
            put("generated", buildJsonObject { put("private_sealed", box.seal(phone.privateKey)); put("public_line", phone.publicLine) })
        }.toString(),
    )
    private val passwordStorage = MemoryStorage()
    private val loginStorage = MemoryStorage()
    private val keys = SshKeyStore(keyStorage, box)
    private val env = SshEnvironment(keys, SshPasswords(passwordStorage, box), { "frp-jump@test" }, TmuxNames(MemoryStorage()))
    private val logins = SshLoginMemory(loginStorage)

    /** An RSA key as PKCS#8 text: the test server cannot verify ed25519 (the one the app makes). */
    private fun syntheticKey(comment: String): SshKeyPair {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = java.io.StringWriter().also { w ->
            org.bouncycastle.util.io.pem.PemWriter(w).use { it.writeObject(org.bouncycastle.util.io.pem.PemObject("PRIVATE KEY", pair.private.encoded)) }
        }.toString()
        return SshKeyPair(pem, publicLineOfPrivateKey(pem, null, comment))
    }

    private fun blob(line: String) = line.split(' ')[1]

    private lateinit var imported: SshKeyPair

    @Before
    fun setUp() {
        val hostKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ -> user == "test" && password == "test" }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                val blob = Base64.getEncoder().encodeToString(ByteArrayBuffer().apply { putRawPublicKey(key) }.compactData)
                offered += blob
                user == "test" && blob in accepted
            }
        }.also { it.start() }
        pin = PinnedHostKey(KeyType.fromKey(hostKey.public).toString(), sshFingerprint(Buffer.PlainBuffer().putPublicKey(hostKey.public).compactData))
    }

    @After
    fun tearDown() = server.stop(true)

    private fun importKey() {
        imported = syntheticKey("imported")
        keys.import(imported.privateKey, null, imported.publicLine)
    }

    private fun login(auth: SshAuth = SshAuth.Key): SshKeySlot? {
        val connector = SshConnector("synthetic-profile", HostKeyStore(MemoryStorage()).apply { pin("dev", 22, pin) }, "dev", 22, server.port, "test", auth, env)
        val ssh = connector.newClient()
        connector.connect(ssh)
        try {
            return connector.authenticate(ssh)
        } finally {
            runCatching { ssh.disconnect() }
        }
    }

    @Test
    fun `only the phone key is known, so it is the phone key`() {
        accepted += blob(phone.publicLine)
        assertEquals(SshKeySlot.PHONE, login())
    }

    @Test
    fun `the device knows the imported key only, so it is the imported key and the phone key is never offered`() {
        importKey()
        accepted += blob(imported.publicLine)
        assertEquals(SshKeySlot.IMPORTED, login())
        assertFalse(offered.contains(blob(phone.publicLine)))
    }

    @Test
    fun `the device knows both, the imported key is tried first and is the one used`() {
        importKey()
        accepted += listOf(blob(phone.publicLine), blob(imported.publicLine))
        assertEquals(SshKeySlot.IMPORTED, login())
    }

    @Test
    fun `an imported key the device refuses falls through to the phone key, which is the one used`() {
        importKey()
        accepted += blob(phone.publicLine)
        assertEquals(SshKeySlot.PHONE, login())
        assertEquals(blob(imported.publicLine), offered.first())
        assertTrue(offered.contains(blob(phone.publicLine)))
    }

    @Test
    fun `a password login has no key`() {
        importKey()
        accepted += blob(imported.publicLine)
        assertNull(login(SshAuth.Password("test", remember = false)))
    }

    @Test
    fun `a key refused by the device is no answer, the login fails`() {
        importKey()
        assertTrue(runCatching { login() }.exceptionOrNull() is net.schmizz.sshj.userauth.UserAuthException)
    }

    @Test
    fun `which key was used is written nowhere`() {
        importKey()
        accepted += blob(imported.publicLine)
        val keysBefore = keyStorage.text
        logins.remember("dev", 22, SshLogin("test", SshAuthMethod.KEY))
        val loginsBefore = loginStorage.text
        assertEquals(SshKeySlot.IMPORTED, login())
        // The login memory, the key file and the passwords hold what they held: no slot, no colour, no flag of the key used.
        assertEquals(loginsBefore, loginStorage.text)
        assertEquals(keysBefore, keyStorage.text)
        assertNull(passwordStorage.text)
        assertEquals(SshLogin("test", SshAuthMethod.KEY), logins.stored("dev", 22))
    }

    @Test
    fun `a login in the memory carries only the user and the method`() {
        val json = WireJson.encodeToString(SshLogin("pi", SshAuthMethod.PASSWORD))
        assertEquals(setOf("user", "method"), kotlinx.serialization.json.Json.parseToJsonElement(json).let { (it as kotlinx.serialization.json.JsonObject).keys })
        val descriptor = SshLogin.serializer().descriptor
        assertEquals(listOf("user", "method"), (0 until descriptor.elementsCount).map(descriptor::getElementName))
    }

    @Test
    fun `the colour names of the keys are the phone blue and the imported purple`() {
        assertEquals("blue", SshKeySlot.PHONE.colorName)
        assertEquals("purple", SshKeySlot.IMPORTED.colorName)
    }
}
