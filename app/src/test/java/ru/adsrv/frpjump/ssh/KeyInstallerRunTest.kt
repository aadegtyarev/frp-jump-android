package ru.adsrv.frpjump.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * "Use key login" end to end against a loopback Apache SSHD (synthetic keys,
 * password "test"): a real `/bin/sh` runs the install script on a scratch
 * home, and key logins are accepted only for keys in that home's
 * `authorized_keys`, so what was installed and what the check offered are
 * both observed on the wire.
 */
class KeyInstallerRunTest {
    /** Runs the exec request as the login shell's `-c`, the key on stdin, in [home]. */
    private class ShCommand(private val line: String, private val home: File, private val ran: AtomicInteger) : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var error: OutputStream? = null
        private var exit: ExitCallback? = null

        override fun setInputStream(`in`: InputStream) { input = `in` }
        override fun setOutputStream(out: OutputStream) { output = out }
        override fun setErrorStream(err: OutputStream) { error = err }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            ran.incrementAndGet()
            val process = ProcessBuilder("/bin/sh", "-c", line).apply { environment()["HOME"] = home.absolutePath }.start()
            val copies = listOf(
                thread(isDaemon = true) { runCatching { input!!.copyTo(process.outputStream); process.outputStream.close() } },
                thread(isDaemon = true) { runCatching { process.inputStream.copyTo(output!!); output!!.flush() } },
                thread(isDaemon = true) { runCatching { process.errorStream.copyTo(error!!); error!!.flush() } },
            )
            thread(isDaemon = true) {
                val status = process.waitFor()
                copies.drop(1).forEach { it.join() }
                exit!!.onExit(status)
            }
        }

        override fun destroy(channel: ChannelSession) = Unit
    }

    private lateinit var home: File
    private lateinit var server: SshServer
    private lateinit var pin: PinnedHostKey
    private val authorized get() = File(home, ".ssh/authorized_keys")

    /** The key blobs (base64) a client offered for a key login (each is asked twice: a probe, then signed). */
    private val offered = CopyOnWriteArrayList<String>()
    private val scriptsRun = AtomicInteger()

    /** Tunnel requests (the `localPort` lambda) and password logins the server saw: both 0 when a run stopped before any connection. */
    private val tunnelsAsked = AtomicInteger()
    private val passwordLogins = AtomicInteger()

    /** Whether keys in `authorized_keys` are accepted (false: sshd forbids key logins). */
    @Volatile
    private var acceptKeys = true

    @Before
    fun setUp() {
        assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
        home = File("build/key-installer-test/${System.nanoTime()}").apply { mkdirs() }
        val hostKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ ->
                passwordLogins.incrementAndGet()
                user == "test" && password == "test"
            }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                val blob = Base64.getEncoder().encodeToString(ByteArrayBuffer().apply { putRawPublicKey(key) }.compactData)
                offered += blob
                user == "test" && acceptKeys && authorized.takeIf { it.isFile }?.readLines().orEmpty().any { it.split(' ').getOrNull(1) == blob }
            }
            commandFactory = CommandFactory { _, command -> ShCommand(command, home, scriptsRun) }
        }.also { it.start() }
        pin = PinnedHostKey(KeyType.fromKey(hostKey.public).toString(), sshFingerprint(Buffer.PlainBuffer().putPublicKey(hostKey.public).compactData))
    }

    @After
    fun tearDown() {
        server.stop(true)
        home.deleteRecursively()
    }

    private val box = FakeKeystore().box("synthetic-ssh")

    /**
     * An RSA key as PKCS#8 text: the test server cannot verify ed25519 (the
     * one the app makes), so the phone's key here is one it can.
     */
    private fun syntheticKey(comment: String): SshKeyPair {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        return SshKeyPair(pem, publicLineOfPrivateKey(pem, null, comment))
    }

    private val env: SshEnvironment = run {
        val phone = syntheticKey("frp-jump@test")
        val stored = buildJsonObject {
            put("generated", buildJsonObject { put("private_sealed", box.seal(phone.privateKey)); put("public_line", phone.publicLine) })
        }
        SshEnvironment(SshKeyStore(MemoryStorage(stored.toString()), box), SshPasswords(MemoryStorage(), box), { "frp-jump@test" }, TmuxNames(MemoryStorage()))
    }
    private val logins = SshLoginMemory(MemoryStorage())
    private val installer = KeyInstaller(env) { _, _, _, _ -> null }

    private fun blob(line: String) = line.split(' ')[1]

    private fun phone() = env.keys.choices("frp-jump@test").first { it.slot == SshKeySlot.PHONE }

    /** A second synthetic key as the imported one. */
    private fun importKey(): SshKeyChoice {
        val pair = syntheticKey("imported")
        env.keys.import(pair.privateKey, null, pair.publicLine)
        return SshKeyChoice(SshKeySlot.IMPORTED, pair.publicLine)
    }

    private fun target(key: SshKeyChoice?, user: String = "test") =
        KeyInstallTarget("synthetic-profile", HostKeyStore(MemoryStorage()).apply { pin("dev", 22, pin) }, "dev", 22, user, logins, { tunnelsAsked.incrementAndGet(); server.port }, key = key)

    private fun run(target: KeyInstallTarget, typed: String? = "test") = runBlocking { installer.install(target, typed) }

    private fun assertNoConnection() {
        assertEquals(0, tunnelsAsked.get())
        assertEquals(0, passwordLogins.get())
        assertEquals(0, scriptsRun.get())
        assertFalse(authorized.exists())
        assertTrue(offered.isEmpty())
    }

    private fun authorizedBlobs() = authorized.takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }.map { it.split(' ')[1] }

    @Test
    fun `the imported key is the one installed and the only one the check offers`() {
        val phone = phone()
        val imported = importKey()
        logins.remember("dev", 22, SshLogin("test", SshAuthMethod.PASSWORD))
        assertEquals(KeyInstallResult.Installed, run(target(imported)))
        assertEquals(listOf(blob(imported.publicLine)), authorizedBlobs())
        assertEquals(listOf(blob(imported.publicLine)), offered.distinct())
        assertFalse(offered.contains(blob(phone.publicLine)))
        assertEquals(SshLogin("test", SshAuthMethod.KEY), logins.stored("dev", 22))
    }

    @Test
    fun `the phone key is the one installed and the only one checked even when there is an imported key`() {
        val phone = phone()
        val imported = importKey()
        assertEquals(KeyInstallResult.Installed, run(target(phone)))
        assertEquals(listOf(blob(phone.publicLine)), authorizedBlobs())
        assertEquals(listOf(blob(phone.publicLine)), offered.distinct())
        assertFalse(offered.contains(blob(imported.publicLine)))
    }

    @Test
    fun `no chosen key means the phone key`() {
        importKey()
        assertEquals(KeyInstallResult.Installed, run(target(null)))
        assertEquals(listOf(blob(phone().publicLine)), authorizedBlobs())
    }

    @Test
    fun `an unreadable key writes nothing and never runs the script`() {
        val broken = syntheticKey("imported")
        env.keys.import("not a private key", null, broken.publicLine)
        logins.remember("dev", 22, SshLogin("test", SshAuthMethod.PASSWORD))
        env.passwords.remember("synthetic-profile", "dev", 22, "test", "test")
        assertEquals(KeyInstallResult.KeyUnreadable, run(target(SshKeyChoice(SshKeySlot.IMPORTED, broken.publicLine)), typed = null))
        assertNoConnection()
        assertEquals(SshLogin("test", SshAuthMethod.PASSWORD), logins.stored("dev", 22))
        assertTrue(env.passwords.has("synthetic-profile", "dev", 22, "test"))
    }

    @Test
    fun `a key removed between the choice and the run writes nothing`() {
        val imported = importKey()
        val chosen = target(imported)
        env.keys.removeImported()
        assertEquals(KeyInstallResult.KeyUnreadable, run(chosen))
        assertNoConnection()
    }

    @Test
    fun `a key replaced between the choice and the run writes nothing`() {
        val imported = importKey()
        val chosen = target(imported)
        importKey()
        assertEquals(KeyInstallResult.KeyUnreadable, run(chosen))
        assertNoConnection()
    }

    @Test
    fun `a line already there is left as it is, whatever its comment, and the login still counts`() {
        val imported = importKey()
        authorized.parentFile.mkdirs()
        val before = "# mine\n${imported.publicLine.substringBeforeLast(' ')} someone@elsewhere\nssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOther other\n"
        authorized.writeText(before)
        assertEquals(KeyInstallResult.Installed, run(target(imported)))
        assertEquals(before, authorized.readText())
    }

    @Test
    fun `the password is forgotten and key login remembered only after the key login worked`() {
        val phone = phone()
        val imported = importKey()
        env.passwords.remember("synthetic-profile", "dev", 22, "test", "test")
        logins.remember("dev", 22, SshLogin("test", SshAuthMethod.PASSWORD))
        acceptKeys = false
        assertEquals(KeyInstallResult.KeyRefused, run(target(phone), typed = null))
        assertEquals(listOf(blob(phone.publicLine)), authorizedBlobs())
        // The refused check offered only the chosen key, never the imported one.
        assertEquals(listOf(blob(phone.publicLine)), offered.distinct())
        assertFalse(offered.contains(blob(imported.publicLine)))
        assertTrue(env.passwords.has("synthetic-profile", "dev", 22, "test"))
        assertEquals(SshLogin("test", SshAuthMethod.PASSWORD), logins.stored("dev", 22))
        acceptKeys = true
        assertEquals(KeyInstallResult.Installed, run(target(phone), typed = null))
        assertFalse(env.passwords.has("synthetic-profile", "dev", 22, "test"))
        assertEquals(SshLogin("test", SshAuthMethod.KEY), logins.stored("dev", 22))
    }

    @Test
    fun `the chosen key survives a user change, a retry and a host key answer`() {
        val imported = importKey()
        val t = target(imported)
        assertEquals(imported, t.withUser("other").key)
        assertEquals(imported, t.withUser("test").key)
        assertEquals(imported, t.withKey(imported).key)
        assertEquals(null, t.withKey(null).key)
        assertEquals("other", t.withUser("other").user)
    }

    @Test
    fun `a key whose private part is not the one of its line writes nothing`() {
        val a = syntheticKey("a")
        val b = syntheticKey("b")
        env.keys.import(a.privateKey, null, b.publicLine)
        assertEquals(KeyInstallResult.KeyUnreadable, run(target(SshKeyChoice(SshKeySlot.IMPORTED, b.publicLine))))
        assertNoConnection()
    }

    /** An RSA key as encrypted PKCS#8 (PBE), the passphrase [pass]. */
    private fun encryptedKey(comment: String, pass: String): SshKeyPair {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val out = java.io.StringWriter()
        val encryptor = org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder(org.bouncycastle.openssl.PKCS8Generator.PBE_SHA1_3DES)
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .setPassword(pass.toCharArray())
            .build()
        org.bouncycastle.openssl.jcajce.JcaPEMWriter(out).use { it.writeObject(org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(pair.private, encryptor)) }
        val pem = out.toString()
        return SshKeyPair(pem, publicLineOfPrivateKey(pem, pass, comment))
    }

    @Test
    fun `an encrypted key is installed and checked with its passphrase`() {
        val key = encryptedKey("imported", "s3cret")
        env.keys.import(key.privateKey, "s3cret", key.publicLine)
        assertEquals(KeyInstallResult.Installed, run(target(SshKeyChoice(SshKeySlot.IMPORTED, key.publicLine))))
        assertEquals(listOf(blob(key.publicLine)), authorizedBlobs())
        assertEquals(listOf(blob(key.publicLine)), offered.distinct())
    }

    @Test
    fun `an encrypted key with a wrong passphrase is unreadable and writes nothing`() {
        val key = encryptedKey("imported", "s3cret")
        env.keys.import(key.privateKey, "wrong", key.publicLine)
        assertEquals(KeyInstallResult.KeyUnreadable, run(target(SshKeyChoice(SshKeySlot.IMPORTED, key.publicLine))))
        assertNoConnection()
    }

    /**
     * The phone's own key, ed25519 as the app makes it. The test sshd has no EdDSA (it is not on the test classpath),
     * so only the install is observed: the key loads, matches its line and its line is written; the check then
     * cannot be answered by that server and ends as refused.
     */
    @Test
    fun `the phones ed25519 key loads and its line is written`() {
        val ownBox = FakeKeystore().box("synthetic-ssh-ed")
        val ownEnv = SshEnvironment(SshKeyStore(MemoryStorage(), ownBox), SshPasswords(MemoryStorage(), ownBox), { "frp-jump@ed" }, TmuxNames(MemoryStorage()))
        val phone = ownEnv.keys.choices("frp-jump@ed").first()
        val result = runBlocking { KeyInstaller(ownEnv) { _, _, _, _ -> null }.install(target(phone), "test") }
        assertEquals(KeyInstallResult.KeyRefused, result)
        assertEquals(listOf(blob(phone.publicLine)), authorizedBlobs())
    }
}
