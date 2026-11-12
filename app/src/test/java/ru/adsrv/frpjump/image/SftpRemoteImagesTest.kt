package ru.adsrv.frpjump.image

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.time.LocalDateTime
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.subsystem.SubsystemFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The upload against a real SFTP subsystem (Apache SSHD on loopback, an ephemeral key, password "test", a temporary
 * folder as the device's whole file system) with a real sshj client: what the device ends up with, not what a fake says.
 */
class SftpRemoteImagesTest {
    private lateinit var root: Path
    private lateinit var server: SshServer
    private val logins = AtomicInteger()
    private val clients = CopyOnWriteArrayList<SSHClient>()

    /** Called before each write the server performs. */
    @Volatile private var onWrite: () -> Unit = {}

    private val images get() = root.resolve(".cache/frp-jump/images")

    @Before fun start() {
        root = Files.createTempDirectory("frpjump-sftp")
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(keys)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> (user == "test" && password == "test").also { if (it) logins.incrementAndGet() } }
            fileSystemFactory = VirtualFileSystemFactory(root)
            commandFactory = org.apache.sshd.server.command.CommandFactory { _, _ -> Quick() }
            subsystemFactories = listOf(SftpSubsystemFactory().apply {
                addSftpEventListener(object : SftpEventListener {
                    override fun writing(session: ServerSession, remoteHandle: String, localHandle: FileHandle, offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) = onWrite()
                })
            })
        }.also { it.start() }
    }

    @After fun stop() {
        clients.forEach { runCatching { it.disconnect() } }
        server.stop(true)
        root.toFile().deleteRecursively()
    }

    private fun client(): SSHClient = SSHClient().apply {
        addHostKeyVerifier(PromiscuousVerifier())
        connect("127.0.0.1", server.port)
        authPassword("test", "test")
        clients += this
    }

    private fun store(ssh: SSHClient = client(), timeoutMs: Int = SftpRemoteImages.REQUEST_TIMEOUT_MS) = SftpRemoteImages.open(ssh, timeoutMs)

    private fun perms(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))

    private fun create(s: RemoteImages, dir: String, name: String, bytes: ByteArray = ByteArray(10) { 1 }) = s.create(dir, name, bytes, {}, {})

    private fun refused(reason: ImageFailure, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: ImageFailureException) {
            assertEquals(reason, e.reason)
        }
    }

    // ---- modes and folders ----

    @Test fun missingFoldersAreCreatedForTheOwnerOnly() {
        store().use { s ->
            assertEquals("/.cache/frp-jump/images", s.prepare())
        }
        for (dir in listOf(root.resolve(".cache"), root.resolve(".cache/frp-jump"), images)) {
            assertTrue(dir.toString(), Files.isDirectory(dir))
            assertEquals(dir.toString(), "rwx------", perms(dir))
        }
    }

    @Test fun anExistingWideImagesFolderIsTightened() {
        Files.createDirectories(images)
        Files.setPosixFilePermissions(images, PosixFilePermissions.fromString("rwxr-xr-x"))
        store().use { it.prepare() }
        assertEquals("rwx------", perms(images))
    }

    @Test fun aNewFileIsForTheOwnerOnlyAndHoldsTheBytes() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        store().use { s ->
            val dir = s.prepare()
            val progress = CopyOnWriteArrayList<Long>()
            var created = false
            s.create(dir, "img-20261112-0130-ab12.jpg", bytes, { created = true }, { progress += it })
            assertTrue(created)
            assertEquals(bytes.size.toLong(), progress.last())
        }
        val file = images.resolve("img-20261112-0130-ab12.jpg")
        assertEquals("rw-------", perms(file))
        assertArrayEquals(bytes, Files.readAllBytes(file))
    }

    @Test fun anExistingFileIsNeverOverwritten() {
        store().use { s ->
            val dir = s.prepare()
            create(s, dir, "img-20261112-0130-ab12.jpg", "first".toByteArray())
            try {
                create(s, dir, "img-20261112-0130-ab12.jpg", "second".toByteArray())
                fail("expected a clash")
            } catch (e: NameTaken) {
            }
        }
        assertEquals("first", String(Files.readAllBytes(images.resolve("img-20261112-0130-ab12.jpg"))))
    }

    @Test fun aClashDoesNotReportTheFileAsCreated() {
        store().use { s ->
            val dir = s.prepare()
            create(s, dir, "img-20261112-0130-ab12.jpg")
            var created = false
            try { s.create(dir, "img-20261112-0130-ab12.jpg", ByteArray(1), { created = true }, {}) } catch (e: NameTaken) {}
            assertFalse(created)
        }
    }

    // ---- links and other things where the folders should be ----

    @Test fun aLinkedAppFolderIsRefused() {
        Files.createDirectories(root.resolve(".cache"))
        Files.createDirectories(root.resolve("elsewhere"))
        Files.createSymbolicLink(root.resolve(".cache/frp-jump"), root.resolve("elsewhere"))
        store().use { s -> refused(ImageFailure.FOLDER_UNSAFE) { s.prepare() } }
        assertTrue("nothing was made through the link", Files.list(root.resolve("elsewhere")).use { it.count() } == 0L)
    }

    @Test fun aLinkedImagesFolderIsRefused() {
        Files.createDirectories(root.resolve(".cache/frp-jump"))
        Files.createDirectories(root.resolve("elsewhere"))
        Files.createSymbolicLink(images, root.resolve("elsewhere"))
        store().use { s -> refused(ImageFailure.FOLDER_UNSAFE) { s.prepare() } }
    }

    @Test fun aFileWhereAFolderShouldBeIsRefused() {
        Files.createDirectories(root.resolve(".cache/frp-jump"))
        Files.write(images, byteArrayOf(1))
        store().use { s -> refused(ImageFailure.FOLDER_UNSAFE) { s.prepare() } }
    }

    @Test fun aCacheFolderThatIsALinkToAFolderIsAccepted() {
        Files.createDirectories(root.resolve("data/cache"))
        Files.createSymbolicLink(root.resolve(".cache"), root.resolve("data/cache"))
        store().use { assertEquals("/.cache/frp-jump/images", it.prepare()) }
        assertTrue(Files.isDirectory(root.resolve("data/cache/frp-jump/images")))
    }

    @Test fun aCacheThatIsAFileIsRefused() {
        Files.write(root.resolve(".cache"), byteArrayOf(1))
        store().use { s -> refused(ImageFailure.FOLDER_UNSAFE) { s.prepare() } }
    }

    // ---- listing and removal ----

    @Test fun aLinkIsListedAsALinkAndRemovalOfANameIsOfThatNameOnly() {
        store().use { s ->
            val dir = s.prepare()
            Files.write(root.resolve("secret.txt"), "keep".toByteArray())
            Files.createSymbolicLink(images.resolve("img-20260101-0000-link.jpg"), root.resolve("secret.txt"))
            create(s, dir, "img-20260101-0000-file.jpg")
            Files.createDirectory(images.resolve("img-20260101-0000-dirx.jpg"))
            val listed = s.list(dir).associateBy { it.name }
            assertTrue(listed.getValue("img-20260101-0000-file.jpg").regularFile)
            assertFalse("a link is not a regular file", listed.getValue("img-20260101-0000-link.jpg").regularFile)
            assertFalse(listed.getValue("img-20260101-0000-dirx.jpg").regularFile)
            s.remove(dir, "img-20260101-0000-file.jpg")
            assertFalse(Files.exists(images.resolve("img-20260101-0000-file.jpg")))
        }
    }

    @Test fun removalRefusesNamesThatAreNotTheApps() {
        store().use { s ->
            val dir = s.prepare()
            try { s.remove(dir, "../../secret"); fail() } catch (e: IllegalArgumentException) {}
            try { s.remove(dir, "notes.txt"); fail() } catch (e: IllegalArgumentException) {}
        }
    }

    // ---- the whole job on a real device ----

    private class Pastes : CopyOnWriteArrayList<Pair<String, Boolean>>()

    private fun target(ssh: SSHClient, timeoutMs: Int = SftpRemoteImages.REQUEST_TIMEOUT_MS, pastes: Pastes = Pastes()) = object : ImageTarget {
        override fun open(track: (java.io.Closeable) -> Unit): RemoteImages = SftpRemoteImages.open(ssh, timeoutMs, track)
        override fun stillCurrent() = ssh.isConnected
        override fun paste(text: String, enter: Boolean): Boolean { pastes += text to enter; return true }
    }

    private fun job(target: ImageTarget, bytes: ByteArray = ByteArray(5000) { 3 }, at: LocalDateTime = LocalDateTime.of(2026, 11, 12, 1, 30)) =
        ImageInsertJob(target, listOf({ _ -> EncodedImage(bytes, ImageFormat.JPEG) }), {}, now = { at }, random = Random(5), insertOn = Dispatchers.Unconfined, closeWith = { it.close() }, cleanupRunner = { it.run() }, removalMs = 5000, cleanupMs = 5000)

    @Test fun theJobInsertsAnAbsolutePathThatNamesTheNewFile() {
        val ssh = client()
        val pastes = Pastes()
        val outcome = runBlocking { withTimeout(30_000) { job(target(ssh, pastes = pastes)).run() } }
        assertEquals(ImageInsertOutcome.Inserted, outcome)
        val (path, enter) = pastes.single()
        assertFalse(enter)
        assertTrue(path, path.startsWith("/.cache/frp-jump/images/img-20261112-0130-"))
        assertTrue(Files.isRegularFile(root.resolve(path.removePrefix("/"))))
        assertEquals("rw-------", perms(root.resolve(path.removePrefix("/"))))
    }

    @Test fun aBatchOfThreeUsesOneChannelAndPastesThreeFilesInOneLine() {
        val ssh = client()
        val pastes = Pastes()
        val opened = SftpRemoteImages.helpersSubmitted.get()
        val at = LocalDateTime.of(2026, 11, 12, 1, 30)
        val sources = (1..3).map { n -> { _: (java.io.Closeable) -> Unit -> EncodedImage(ByteArray(1000 * n) { n.toByte() }, ImageFormat.JPEG) } }
        val job = ImageInsertJob(target(ssh, pastes = pastes), sources, {}, now = { at }, random = Random(5), insertOn = Dispatchers.Unconfined, closeWith = { it.close() }, cleanupRunner = { it.run() }, removalMs = 5000, cleanupMs = 5000)
        assertEquals(ImageInsertOutcome.Inserted, runBlocking { withTimeout(30_000) { job.run() } })
        assertEquals("one SFTP channel opened for the whole batch", 1, SftpRemoteImages.helpersSubmitted.get() - opened)
        val (line, enter) = pastes.single()
        assertFalse(enter)
        val paths = line.split(' ')
        assertEquals(3, paths.size)
        assertEquals(listOf(1000L, 2000L, 3000L), paths.map { Files.size(root.resolve(it.removePrefix("/"))) })
        paths.forEach { assertEquals("rw-------", perms(root.resolve(it.removePrefix("/")))) }
    }

    @Test fun theUploadUsesTheOpenConnectionAndLogsInNoMore() {
        val ssh = client()
        assertEquals(1, logins.get())
        runBlocking { withTimeout(30_000) { job(target(ssh)).run() } }
        runBlocking { withTimeout(30_000) { job(target(ssh)).run() } }
        assertEquals("the channel is new, the login is not", 1, logins.get())
        assertTrue("the connection is still up", ssh.isConnected && ssh.isAuthenticated)
    }

    @Test fun cleanupOnTheDeviceDeletesOnlyOldOwnRegularFilesAndNeverFollowsLinks() {
        val ssh = client()
        store(ssh).use { it.prepare() }
        val old = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(2 * 3600))
        val recent = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(10 * 60))
        fun put(name: String, time: java.nio.file.attribute.FileTime) { val p = images.resolve(name); Files.write(p, byteArrayOf(1)); Files.setLastModifiedTime(p, time) }
        put("img-20260101-0000-old1.jpg", old)
        put("img-20260101-0000-old2.png", old)
        put("img-20260101-0000-new1.jpg", recent)
        put("img-20260101-0000-old3.gif", old)
        put("notes.txt", old)
        val outside = root.resolve("outside.txt").also { Files.write(it, "outside".toByteArray()); Files.setLastModifiedTime(it, old) }
        // The link's own time and the folder's are old too: only their kind keeps them.
        val link = images.resolve("img-20260101-0000-lnk1.jpg")
        Files.createSymbolicLink(link, outside)
        Files.getFileAttributeView(link, java.nio.file.attribute.BasicFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).setTimes(old, old, old)
        val folder = images.resolve("img-20260101-0000-dir1.jpg")
        Files.createDirectory(folder)
        Files.setLastModifiedTime(folder, old)
        runBlocking { withTimeout(30_000) { job(target(ssh)).run() } }
        val left = Files.list(images).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
        assertFalse("old1", "img-20260101-0000-old1.jpg" in left)
        assertFalse("old2", "img-20260101-0000-old2.png" in left)
        assertTrue("recent one stays", "img-20260101-0000-new1.jpg" in left)
        assertTrue("other extension stays", "img-20260101-0000-old3.gif" in left)
        assertTrue("foreign name stays", "notes.txt" in left)
        assertTrue("a link is skipped", "img-20260101-0000-lnk1.jpg" in left)
        assertTrue("its target is untouched", Files.exists(outside))
        assertTrue("a folder with an own name is skipped", Files.isDirectory(images.resolve("img-20260101-0000-dir1.jpg")))
    }

    @Test fun cancelMidWriteRemovesThePartFileAndInsertsNothing() {
        val ssh = client()
        val pastes = Pastes()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        onWrite = { writing.countDown(); release.await(20, TimeUnit.SECONDS) }
        try {
            val cancelled = runBlocking {
                val running = async(Dispatchers.Default) { job(target(ssh, pastes = pastes), ByteArray(300_000) { 9 }).run() }
                assertTrue("the server is in a write", writing.await(10, TimeUnit.SECONDS))
                running.cancel()
                awaitCancelled(running)
            }
            assertTrue(cancelled)
        } finally {
            onWrite = {}
            release.countDown()
        }
        assertTrue(pastes.isEmpty())
        val left = if (Files.isDirectory(images)) Files.list(images).use { s -> s.toList() } else emptyList()
        assertTrue("no image file left: $left", left.isEmpty())
        assertTrue("the tab's connection is not touched", ssh.isConnected && ssh.isAuthenticated)
    }

    @Test fun aSlowTunnelIsNotAFailureWhileEveryRequestIsAnsweredInTime() {
        val ssh = client()
        val pastes = Pastes()
        // 24 writes of 32 KiB, each answered after 150 ms: about 3.6 s in all, against a 1 s wait per request.
        onWrite = { Thread.sleep(150) }
        val started = System.nanoTime()
        val outcome = runBlocking { withTimeout(60_000) { job(target(ssh, timeoutMs = 1000, pastes = pastes), ByteArray(24 * 32 * 1024) { 5 }).run() } }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(ImageInsertOutcome.Inserted, outcome)
        assertTrue("the upload took $tookMs ms, more than one request timeout", tookMs > 1500)
        assertEquals(24 * 32 * 1024, Files.size(root.resolve(pastes.single().first.removePrefix("/"))).toInt())
    }

    @Test fun aServerThatStopsAnsweringFailsAfterTheRequestTimeout() {
        val ssh = client()
        val stuck = CountDownLatch(1)
        onWrite = { stuck.await(20, TimeUnit.SECONDS) }
        val started = System.nanoTime()
        try {
            store(ssh, timeoutMs = 700).use { s ->
                val dir = s.prepare()
                try {
                    s.create(dir, "img-20261112-0130-ab12.jpg", ByteArray(3 * 32 * 1024), {}, {})
                    fail("expected a timeout")
                } catch (e: IOException) {
                    // The request wait ended; nothing says it took the whole upload.
                }
            }
        } finally {
            stuck.countDown()
        }
        assertTrue("failed within seconds, not at some total deadline", (System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test fun aServerWithoutSftpIsReportedAsSuch() {
        server.subsystemFactories = emptyList()
        val ssh = client()
        try {
            SftpRemoteImages.open(ssh)
            fail("expected SFTP off")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        assertTrue(ssh.isConnected)
    }

    // ---- fix pass: opening that hangs or speaks garbage, listing limit, permission ----

    /** A subsystem named sftp that is started and then says [garbage] (or nothing) and never answers; counts its channels. */
    private class Broken(val garbage: ByteArray?, val onDestroy: ByteArray? = null) : SubsystemFactory {
        val started = AtomicInteger()
        val destroyed = AtomicInteger()
        val startedLatch = CountDownLatch(1)
        override fun getName() = "sftp"
        override fun createSubsystem(channel: ChannelSession): Command = object : Command {
            private var out: OutputStream? = null
            override fun setInputStream(`in`: InputStream) = Unit
            override fun setOutputStream(out: OutputStream) { this.out = out }
            override fun setErrorStream(err: OutputStream) = Unit
            override fun setExitCallback(callback: ExitCallback) = Unit
            override fun start(channel: ChannelSession, env: Environment) {
                started.incrementAndGet()
                garbage?.let { out?.write(it); out?.flush() }
                startedLatch.countDown()
            }
            override fun destroy(channel: ChannelSession) {
                // What a server's last words look like: bytes written as the channel goes, after the client's CLOSE.
                onDestroy?.let { runCatching { out?.write(it); out?.flush() } }
                destroyed.incrementAndGet()
            }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < until)
            Thread.sleep(25)
        }
    }

    @Test fun aSubsystemThatNeverAnswersFailsAsNotAvailableWithinTheBoundAndLeavesNoChannel() {
        val broken = Broken(null)
        server.subsystemFactories = listOf(broken)
        val ssh = client()
        val started = System.nanoTime()
        try {
            SftpRemoteImages.open(ssh, requestTimeoutMs = 600)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        assertTrue("bounded by the request timeout, not hung", (System.nanoTime() - started) / 1_000_000 < 8_000)
        waitFor("the server side of the channel to be destroyed") { broken.destroyed.get() == broken.started.get() && broken.started.get() == 1 }
        assertTrue("the tab's own connection is untouched", ssh.isConnected && ssh.isAuthenticated)
    }

    @Test fun cancellingAnOpeningThatHangsEndsItAtOnceAndClosesTheChannel() {
        val broken = Broken(null)
        server.subsystemFactories = listOf(broken)
        val ssh = client()
        val handle = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
        val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        // A long timeout: only Cancel can end it in time.
        val thread = Thread {
            try { SftpRemoteImages.open(ssh, requestTimeoutMs = 60_000) { handle.set(it) } } catch (e: Throwable) { result.set(e) }
            done.countDown()
        }.apply { start() }
        assertTrue(broken.startedLatch.await(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        handle.get()!!.close()
        assertTrue("the opening ended", done.await(10, TimeUnit.SECONDS))
        assertTrue("at once", (System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue("as a cancellation, an IOException", result.get() is IOException)
        waitFor("the channel to be destroyed") { broken.destroyed.get() == 1 }
        thread.join()
    }

    @Test fun garbageInTheChannelIsNotAvailableAndTheChannelIsClosed() {
        // A .bashrc that prints something: the first four bytes read as a huge packet length.
        val broken = Broken("Welcome back!\n".toByteArray())
        server.subsystemFactories = listOf(broken)
        val ssh = client()
        try {
            SftpRemoteImages.open(ssh, requestTimeoutMs = 3_000)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        waitFor("the channel to be destroyed") { broken.destroyed.get() == 1 }
    }

    @Test fun repeatedFailedOpeningsDoNotLeakChannels() {
        val broken = Broken(null)
        server.subsystemFactories = listOf(broken)
        val ssh = client()
        repeat(15) {
            try {
                SftpRemoteImages.open(ssh, requestTimeoutMs = 250)
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
            }
        }
        waitFor("every opened channel to be destroyed on the server") { broken.started.get() == 15 && broken.destroyed.get() == 15 }
    }

    @Test fun anOpeningOnADroppedConnectionIsAnIoErrorNotNotAvailable() {
        val ssh = client()
        ssh.disconnect()
        try {
            SftpRemoteImages.open(ssh, requestTimeoutMs = 1_000)
            fail("expected an error")
        } catch (e: ImageFailureException) {
            fail("a gone connection is not 'not available': ${e.reason}")
        } catch (e: IOException) {
        }
    }

    @Test fun listingStopsAfterTheLimit() {
        store().use { s ->
            val dir = s.prepare()
            repeat(30) { Files.write(images.resolve("img-20260101-0000-%04d.jpg".format(it).replace("-0000-0", "-0000-a")), byteArrayOf(1)) }
            assertEquals(10, s.list(dir, 10).size)
            assertEquals(30, s.list(dir, 500).size)
        }
    }

    @Test fun aDeviceThatSaysPermissionDeniedIsTold() {
        store().use { s ->
            val dir = s.prepare()
            Files.setPosixFilePermissions(images, PosixFilePermissions.fromString("r-x------"))
            try {
                try {
                    create(s, dir, "img-20261112-0130-ab12.jpg")
                    fail("expected a refusal")
                } catch (e: ImageFailureException) {
                    assertEquals(ImageFailure.PERMISSION_DENIED, e.reason)
                }
            } finally {
                Files.setPosixFilePermissions(images, PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    /**
     * Awaits [running] after it was cancelled. A timeout is also a CancellationException, so the time is what proves the
     * cancel took effect: it must end well before any hook's long wait, not at the timeout.
     */
    private suspend fun awaitCancelled(running: kotlinx.coroutines.Deferred<*>, limitMs: Long = 7_000): Boolean {
        val started = System.nanoTime()
        val cancelled = try { withTimeout(20_000) { running.await() }; false } catch (e: CancellationException) { true }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("ended after $ms ms: the cancel did not take effect at once", ms < limitMs)
        return cancelled
    }

    // ---- second fix pass: the opening runs on an abandonable helper ----

    /**
     * A session factory that stands in for sshj waiting on the network: the channel open ([openBlocks]) or the subsystem
     * request ([subsystemBlocks]) does not return until its gate is released, as a stalled tunnel behaves for up to 30 s.
     */
    private class Stalled(val openBlocks: CountDownLatch? = null, val subsystemBlocks: CountDownLatch? = null, val fails: Throwable? = null) : net.schmizz.sshj.connection.channel.direct.SessionFactory {
        val made = AtomicInteger()
        val closed = AtomicInteger()
        val streamsClosed = AtomicInteger()
        /** The helper is inside the (stalled) channel open. */
        val entered = CountDownLatch(1)
        override fun getRemoteSocketAddress() = java.net.InetSocketAddress.createUnresolved("stalled", 22)
        override fun startSession(): net.schmizz.sshj.connection.channel.direct.Session {
            entered.countDown()
            openBlocks?.await(30, TimeUnit.SECONDS)
            fails?.let { throw it }
            made.incrementAndGet()
            val input = object : InputStream() {
                override fun read(): Int = -1
                override fun close() { streamsClosed.incrementAndGet() }
            }
            return java.lang.reflect.Proxy.newProxyInstance(
                net.schmizz.sshj.connection.channel.direct.Session::class.java.classLoader,
                arrayOf(net.schmizz.sshj.connection.channel.direct.Session::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getLoggerFactory" -> net.schmizz.sshj.common.LoggerFactory.DEFAULT
                    "getInputStream" -> input
                    "startSubsystem" -> { subsystemBlocks?.await(30, TimeUnit.SECONDS); throw net.schmizz.sshj.connection.ConnectionException("stalled") }
                    "close" -> { closed.incrementAndGet(); null }
                    "hashCode" -> 1
                    "equals" -> false
                    "toString" -> "stalled session"
                    else -> null
                }
            } as net.schmizz.sshj.connection.channel.direct.Session
        }
    }

    private fun openStalled(factory: Stalled, timeoutMs: Int, track: (java.io.Closeable) -> Unit = {}) =
        SftpRemoteImages.open(factory, { true }, timeoutMs, track)

    @Test fun aChannelOpenThatNeverAnswersEndsAtTheBoundAndTheLateChannelIsClosed() {
        val gate = CountDownLatch(1)
        val stalled = Stalled(openBlocks = gate)
        val started = System.nanoTime()
        try {
            openStalled(stalled, 500)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("ended at about the bound, not at sshj's 30 s: $ms ms", ms in 400..4_000)
        assertEquals("the helper is still waiting for the channel", 0, stalled.made.get())
        // The network finally answers: the channel that appears now must not stay open.
        gate.countDown()
        waitFor("the late channel to be made and closed") { stalled.made.get() == 1 && stalled.closed.get() == 1 }
    }

    @Test fun cancelEndsAChannelOpenThatNeverAnswersAtOnce() {
        val gate = CountDownLatch(1)
        val stalled = Stalled(openBlocks = gate)
        val handle = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
        val tracked = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        // A long bound: only Cancel can end it in time.
        Thread {
            try { openStalled(stalled, 60_000) { handle.set(it); tracked.countDown() } } catch (e: Throwable) { result.set(e) }
            done.countDown()
        }.start()
        assertTrue("the opening is under way", tracked.await(5, TimeUnit.SECONDS))
        assertTrue("the helper is inside the channel open", stalled.entered.await(5, TimeUnit.SECONDS))
        val cancelled = System.nanoTime()
        handle.get()!!.close()
        assertTrue("the caller was released", done.await(3, TimeUnit.SECONDS))
        assertTrue("at once, not after sshj's wait", (System.nanoTime() - cancelled) / 1_000_000 < 2_000)
        assertTrue("as a cancellation", result.get() is IOException && result.get() !is ImageFailureException)
        gate.countDown()
        waitFor("the late channel to be closed") { stalled.made.get() == 1 && stalled.closed.get() == 1 }
    }

    @Test fun aSubsystemRequestThatNeverAnswersEndsAtTheBoundClosesTheChannelAndItsStream() {
        val gate = CountDownLatch(1)
        val stalled = Stalled(subsystemBlocks = gate)
        val started = System.nanoTime()
        try {
            openStalled(stalled, 500)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        assertTrue("at about the bound", (System.nanoTime() - started) / 1_000_000 in 400..4_000)
        // Closed from the caller's side without waiting for the helper or for the peer; its input stream is never ended by us.
        waitFor("the channel to be closed") { stalled.closed.get() == 1 }
        assertEquals(0, stalled.streamsClosed.get())
        gate.countDown()
    }

    @Test fun helpersAreCappedAtSixAndTheSeventhIsRefusedAtOnce() {
        waitFor("no helper left from before") { SftpRemoteImages.helpersRunning() == 0 }
        val gate = CountDownLatch(1)
        val factories = List(8) { Stalled(openBlocks = gate) }
        var refusedAt = -1
        for ((i, f) in factories.withIndex()) {
            try {
                openStalled(f, 150)
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
                // The cap's refusal, not a timeout: sshj's executor said no.
                if (e.cause is java.util.concurrent.RejectedExecutionException) { refusedAt = i; break }
            }
        }
        assertEquals("the seventh opening is the first refused", 6, refusedAt)
        gate.countDown()
        waitFor("the six late channels to be made and closed") { factories.take(6).all { it.made.get() == 1 && it.closed.get() == 1 } }
        assertEquals("the refused opening never reached its factory", 0, factories[6].made.get())
    }

    @Test fun aBannerWhoseFirstByteIsSmallDoesNotLeaveAChannelOrCrash() {
        for (banner in listOf("\nHello world\n", "1234 this is not sftp\n")) {
            val broken = Broken(banner.toByteArray())
            server.subsystemFactories = listOf(broken)
            val ssh = client()
            try {
                SftpRemoteImages.open(ssh, requestTimeoutMs = 2_000)
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
            }
            waitFor("the channel to be destroyed") { broken.destroyed.get() == 1 }
            assertTrue(ssh.isConnected && ssh.isAuthenticated)
        }
    }

    @Test fun anErrorInTheHelperIsAFailureNotAnUncaughtCrash() {
        // sshj allocates what a garbage packet length says: an OutOfMemoryError on the helper thread must not escape it.
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            try {
                openStalled(Stalled(fails = OutOfMemoryError("banner")), 2_000)
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
            }
            Thread.sleep(300)
            assertTrue("nothing escaped the helper: $uncaught", uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun aCancelAfterTheStoreWasHandedOverDoesNotCloseIt() {
        val ssh = client()
        val handle = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
        val store = SftpRemoteImages.open(ssh, requestTimeoutMs = 5_000) { handle.set(it) }
        try {
            // The job's closer still holds the opening's handle and calls it at the end (or on Cancel): the store was
            // handed over to its owner (the cleanup), which closes it itself.
            handle.get()!!.close()
            assertEquals("/.cache/frp-jump/images", store.prepare())
        } finally {
            store.close()
        }
    }

    @Test fun theDevicesStatusIsToldTruthfully() {
        val e = net.schmizz.sshj.sftp.SFTPException("x")
        val codes = net.schmizz.sshj.sftp.Response.StatusCode.values().associateBy { it.name }
        fun reason(name: String) = (SftpRemoteImages.mapStatus(codes.getValue(name), e) as ImageFailureException).reason
        assertEquals(ImageFailure.PERMISSION_DENIED, reason("PERMISSION_DENIED"))
        assertEquals("write protect is not 'permission denied'", ImageFailure.SAVE_FAILED, reason("WRITE_PROTECT"))
        assertEquals(ImageFailure.SAVE_FAILED, reason("FAILURE"))
        assertEquals(ImageFailure.SAVE_FAILED, reason("NO_SUCH_FILE"))
        assertEquals(ImageFailure.NO_SPACE, reason("NO_SPACE_ON_FILESYSTEM"))
        assertEquals(ImageFailure.NO_SPACE, reason("QUOTA_EXCEEDED"))
        assertTrue(SftpRemoteImages.mapStatus(codes.getValue("FILE_ALREADY_EXISTS"), e) is NameTaken)
        assertTrue("a lost connection stays itself", SftpRemoteImages.mapStatus(codes.getValue("CONNECITON_LOST"), e) === e)
    }

    // ---- third fix pass: a channel's input stream is never ended from our side ----

    /** An exec command that succeeds at once: proves the connection's shell side still works. */
    private class Quick : Command {
        private var callback: ExitCallback? = null
        override fun setInputStream(`in`: InputStream) = Unit
        override fun setOutputStream(out: OutputStream) = Unit
        override fun setErrorStream(err: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { this.callback = callback }
        override fun start(channel: ChannelSession, env: Environment) { callback?.onExit(0) }
        override fun destroy(channel: ChannelSession) = Unit
    }

    private fun assertConnectionAndShellSurvive(ssh: SSHClient, sftpToo: Boolean) {
        assertTrue("the connection is up", ssh.isConnected && ssh.isAuthenticated)
        ssh.startSession().use { s ->
            val command = s.exec("true")
            command.join(5, TimeUnit.SECONDS)
            assertEquals("a new exec channel works", 0, command.exitStatus)
        }
        if (sftpToo) store(ssh).use { assertEquals("a new SFTP channel works", "/.cache/frp-jump/images", it.prepare()) }
    }

    @Test fun cancelMidUploadWithRepliesInFlightKeepsTheConnectionAndTheShell() {
        val ssh = client()
        val progressed = CountDownLatch(1)
        // Each write is answered 30 ms after it arrives, so up to 16 answers are on their way when Cancel closes the channel.
        onWrite = { Thread.sleep(30) }
        val cancelled = runBlocking {
            val running = async(Dispatchers.Default) {
                ImageInsertJob(
                    target(ssh), listOf({ _ -> EncodedImage(ByteArray(2 * 1024 * 1024) { 4 }, ImageFormat.JPEG) }),
                    { if (it is ImageInsertState.Uploading && it.sent > 200_000) progressed.countDown() },
                    now = { LocalDateTime.of(2026, 11, 12, 1, 30) }, random = Random(5), insertOn = Dispatchers.Unconfined,
                ).run()
            }
            assertTrue("the upload is under way", progressed.await(15, TimeUnit.SECONDS))
            running.cancel()
            awaitCancelled(running)
        }
        assertTrue(cancelled)
        onWrite = {}
        // The late answers arrive now: they must not take the connection down.
        Thread.sleep(700)
        assertConnectionAndShellSurvive(ssh, sftpToo = true)
    }

    @Test fun aBannerThatFailsTheOpeningAndIsFollowedByMoreBytesLeavesTheConnectionUp() {
        // The server sends more bytes as its channel is being closed, after our CLOSE: they arrive on a channel we closed.
        val broken = Broken("\nHello world\n".toByteArray(), onDestroy = ByteArray(64) { 1 })
        server.subsystemFactories = listOf(broken)
        val ssh = client()
        try {
            SftpRemoteImages.open(ssh, requestTimeoutMs = 3_000)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        waitFor("the channel to be destroyed") { broken.destroyed.get() == 1 }
        Thread.sleep(700)
        assertConnectionAndShellSurvive(ssh, sftpToo = false)
    }

    @Test fun theBoundHitWhileTheVersionIsOutstandingAndTheVersionThenArrivingLateLeavesTheConnectionUp() {
        // The server answers INIT only as it is closed: a SFTP version packet (length 5, type 2, version 3) after our CLOSE.
        val version = byteArrayOf(0, 0, 0, 5, 2, 0, 0, 0, 3)
        val broken = Broken(null, onDestroy = version)
        server.subsystemFactories = listOf(broken)
        val ssh = client()
        try {
            SftpRemoteImages.open(ssh, requestTimeoutMs = 600)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        waitFor("the channel to be destroyed") { broken.destroyed.get() == 1 }
        Thread.sleep(700)
        assertConnectionAndShellSurvive(ssh, sftpToo = false)
    }

    @Test fun aCancelThatCameFirstWinsOverAnOpeningThatHadFinished() {
        val ssh = client()
        val handle = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
        try {
            // Cancel arrives after the helper finished and before the caller settles: the closed store is not handed over.
            SftpRemoteImages.open(ssh as net.schmizz.sshj.connection.channel.direct.SessionFactory, { true }, 5_000, { handle.set(it) }, beforeSettle = { handle.get()!!.close() })
            fail("expected a cancellation")
        } catch (e: ImageFailureException) {
            fail("a cancellation is not a device failure: ${e.reason}")
        } catch (e: IOException) {
            // cancelled
        }
        waitFor("no helper left") { SftpRemoteImages.helpersRunning() == 0 }
        assertConnectionAndShellSurvive(ssh, sftpToo = true)
    }

    @Test fun aCancelBeforeTheOpeningBeginsSubmitsNothing() {
        val stalled = Stalled()
        val submittedBefore = SftpRemoteImages.helpersSubmitted.get()
        try {
            openStalled(stalled, 5_000) { it.close() }
            fail("expected a cancellation")
        } catch (e: ImageFailureException) {
            fail("a cancellation is not a device failure")
        } catch (e: IOException) {
        }
        Thread.sleep(300)
        assertEquals("no helper ran, no channel was made", 0, stalled.made.get())
        assertEquals("not even submitted", submittedBefore, SftpRemoteImages.helpersSubmitted.get())
    }

    @Test fun anAbandonedHelperIsInterruptedOnceItsChannelExistsAndNeverClosesAStream() {
        waitFor("no helper left from before") { SftpRemoteImages.helpersRunning() == 0 }
        // The subsystem request never answers and nothing releases it: only the interrupt (sshj would wait 30 s) ends it.
        val stalled = Stalled(subsystemBlocks = CountDownLatch(1))
        try {
            openStalled(stalled, 400)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.NOT_AVAILABLE, e.reason)
        }
        waitFor("the abandoned helper to end, well before sshj's 30 s", 5_000) { SftpRemoteImages.helpersRunning() == 0 }
        waitFor("the channel to be closed") { stalled.closed.get() == 1 }
        assertEquals("a channel's input stream is never ended from our side", 0, stalled.streamsClosed.get())
    }

    @Test fun aStalledConnectionCannotUseUpTheOpeningsOfOtherConnections() {
        waitFor("no helper left from before") { SftpRemoteImages.helpersRunning() == 0 }
        val gate = CountDownLatch(1)
        val stalledTab = Stalled(openBlocks = gate)
        // Insert, then Cancel, again and again on the one stalled connection: two abandoned helpers are all it may keep.
        repeat(2) {
            try { openStalled(stalledTab, 100); fail("expected a refusal") } catch (e: ImageFailureException) { assertEquals(ImageFailure.NOT_AVAILABLE, e.reason) }
        }
        val started = System.nanoTime()
        try { openStalled(stalledTab, 5_000); fail("expected a refusal") } catch (e: ImageFailureException) { assertEquals(ImageFailure.NOT_AVAILABLE, e.reason) }
        assertTrue("the third is refused at once", (System.nanoTime() - started) / 1_000_000 < 500)
        // Another connection still opens (here: it answers, and is told the subsystem is not available, not "too many").
        val healthy = Stalled()
        val begun = System.nanoTime()
        try { openStalled(healthy, 5_000); fail("expected a refusal") } catch (e: ImageFailureException) { assertEquals(ImageFailure.NOT_AVAILABLE, e.reason) }
        assertEquals("the healthy connection's helper ran", 1, healthy.made.get())
        assertTrue(begun > 0)
        gate.countDown()
        waitFor("the late channels to be closed") { stalledTab.made.get() == stalledTab.closed.get() }
    }

    @Test fun aRefusedOpeningThatIsCancelledLaterIsNotCountedAsAnAbandonedHelper() {
        waitFor("no helper left from before") { SftpRemoteImages.helpersRunning() == 0 }
        val gate = CountDownLatch(1)
        // Fill the whole pool with stalled openings on other connections.
        val blockers = List(6) { Stalled(openBlocks = gate) }
        for (b in blockers) {
            try { openStalled(b, 100); fail("expected a refusal") } catch (e: ImageFailureException) { assertEquals(ImageFailure.NOT_AVAILABLE, e.reason) }
        }
        val tab = Stalled()
        // The pool says no (twice); the job's closer then calls the opening's handle, as it does at the end of a job.
        repeat(2) {
            val handle = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
            try {
                openStalled(tab, 2_000) { handle.set(it) }
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertTrue("refused by the pool", e.cause is java.util.concurrent.RejectedExecutionException)
            }
            handle.get()!!.close()
        }
        assertEquals("no helper ever ran for this connection, so none is counted", 0, SftpRemoteImages.abandonedCount(tab))
        gate.countDown()
        waitFor("the pool to be free again") { SftpRemoteImages.helpersRunning() == 0 }
        // The healthy tab still opens (here: its helper runs and is told the subsystem is not available).
        try { openStalled(tab, 3_000); fail("expected a refusal") } catch (e: ImageFailureException) { assertEquals(ImageFailure.NOT_AVAILABLE, e.reason) }
        assertEquals("the connection's helper ran", 1, tab.made.get())
    }
}
