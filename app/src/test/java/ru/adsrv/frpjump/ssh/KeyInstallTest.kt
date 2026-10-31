package ru.adsrv.frpjump.ssh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import ru.adsrv.frpjump.R
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** "Use key login": the script as the device's sh runs it, and what each ending says. */
class KeyInstallTest {
    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleExampleExampleExampleExampleExampl frp-jump@phone"
    private lateinit var home: File
    private val ssh get() = File(home, ".ssh")
    private val authorized get() = File(ssh, "authorized_keys")

    @Before
    fun setUp() {
        // Under the build directory, not the system's temp.
        home = File("build/key-install-test/${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        home.deleteRecursively()
    }

    /** Runs the exec request as sshd would: the login shell's `-c`, the key on stdin. */
    private fun install(line: String = key): Pair<Int, String> {
        assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
        val process = ProcessBuilder("/bin/sh", "-c", keyInstallCommand()).apply { environment()["HOME"] = home.absolutePath }.start()
        process.outputStream.use { it.write(keyInstallInput(line).toByteArray()) }
        val err = process.errorStream.bufferedReader().readText()
        return process.waitFor() to err
    }

    private fun lines() = authorized.readText().lines().filter { it.isNotEmpty() }

    @Test
    fun `the script needs no quoting of its own and is one line`() {
        assertFalse(KEY_INSTALL_SCRIPT.contains('\''))
        assertFalse(KEY_INSTALL_SCRIPT.contains('\n'))
    }

    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))

    @Test
    fun `a key already there ends it without touching anything, read-only too`() {
        ssh.mkdirs()
        authorized.writeText(key + "\n")
        Files.setPosixFilePermissions(authorized.toPath(), PosixFilePermissions.fromString("r--r--r--"))
        Files.setPosixFilePermissions(ssh.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        try {
            val modified = authorized.lastModified()
            assertEquals(0, install().first)
            assertEquals(listOf(key), lines())
            // Nothing was made private either: there was nothing to do.
            assertEquals("r--r--r--", mode(authorized))
            assertEquals("rwxr-xr-x", mode(ssh))
            assertEquals(modified, authorized.lastModified())
        } finally {
            Files.setPosixFilePermissions(authorized.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a file it may not write fails with what sh said and is left as it was`() {
        // Behind a symlink, so the script does not chmod it writable: as one the user does not own.
        ssh.mkdirs()
        val real = File(home, "keys").apply { writeText("ssh-rsa AAAAB3Other other@host\n") }
        Files.setPosixFilePermissions(real.toPath(), PosixFilePermissions.fromString("r--r--r--"))
        Files.createSymbolicLink(authorized.toPath(), real.toPath().toAbsolutePath())
        try {
            // Root may write anyway: then there is nothing to see here.
            assumeTrue(!real.canWrite())
            val (status, err) = install()
            assertTrue(status != 0)
            assertTrue(commandFailure(status, err)!!.detail.isNotBlank())
            assertEquals(listOf("ssh-rsa AAAAB3Other other@host"), real.readText().lines().filter { it.isNotEmpty() })
            assertEquals("r--r--r--", mode(real))
        } finally {
            Files.setPosixFilePermissions(real.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a symlinked authorized_keys gets the key in its target, whose mode is not changed`() {
        ssh.mkdirs()
        val real = File(home, "keys").apply { writeText("") }
        Files.setPosixFilePermissions(real.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        Files.createSymbolicLink(authorized.toPath(), real.toPath().toAbsolutePath())
        assertEquals(0, install().first)
        assertTrue(Files.isSymbolicLink(authorized.toPath()))
        assertEquals(listOf(key), real.readText().lines().filter { it.isNotEmpty() })
        assertEquals("rw-r--r--", mode(real))
    }

    @Test
    fun `a script that does not end is closed and reported as timed out, its stderr not read`() {
        var closed = false
        var read = false
        val result = scriptOutcome(end = { ScriptEnd.TIMED_OUT }, status = { null }, stderr = { read = true; "" }, close = { closed = true })
        assertEquals(KeyInstallResult.CommandFailed(SCRIPT_TIMED_OUT), result)
        assertTrue(closed)
        assertFalse(read)
    }

    @Test
    fun `a script that ended is read after it`() {
        var closed = false
        assertNull(scriptOutcome(end = { ScriptEnd.DONE }, status = { 0 }, stderr = { "" }, close = { closed = true }))
        assertEquals(KeyInstallResult.CommandFailed("nope"), scriptOutcome({ ScriptEnd.DONE }, { 1 }, { "nope\n" }, { closed = true }))
        assertFalse(closed)
    }

    @Test
    fun `a connection that went while the script ran is a connection failure, not a timeout`() {
        var read = false
        val result = scriptOutcome(end = { ScriptEnd.DROPPED }, status = { null }, stderr = { read = true; "" }, close = {})
        assertEquals(KeyInstallResult.ConnectionFailed(CONNECTION_DROPPED), result)
        assertFalse(read)
    }

    @Test
    fun `known key types with a base64 body pass`() {
        listOf(
            key,
            "ssh-rsa AAAAB3NzaC1yc2E= me",
            "ecdsa-sha2-nistp256 AAAAE2VjZHNh",
            "sk-ssh-ed25519@openssh.com AAAAGnNr",
        ).forEach { keyInstallInput(it) }
    }

    @Test
    fun `a bad key line is refused`() {
        listOf(
            "ssh-dss AAAAB3NzaC1kc3M= old",
            "command=\"x\" ssh-ed25519 AAAA",
            "ssh-ed25519 not*base64",
            "ssh-ed25519 AAAA comment\u0007bell",
            "ssh-ed25519 AAAA\tx",
        ).forEach { line ->
            try {
                keyInstallInput(line)
                throw AssertionError("accepted: $line")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `a fresh home gets a private ssh folder with the key`() {
        assertEquals(0, install().first)
        assertEquals(listOf(key), lines())
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(ssh.toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(authorized.toPath())))
    }

    @Test
    fun `installing twice keeps one line`() {
        install()
        assertEquals(0, install().first)
        assertEquals(listOf(key), lines())
    }

    @Test
    fun `the same key under another comment is not added again`() {
        ssh.mkdirs()
        val other = key.substringBeforeLast(' ') + " me@laptop"
        authorized.writeText(other + "\n")
        install()
        assertEquals(listOf(other), lines())
    }

    @Test
    fun `other keys stay and a last line without a newline is not joined`() {
        ssh.mkdirs()
        authorized.writeText("ssh-rsa AAAAB3Other other@host")
        install()
        assertEquals(listOf("ssh-rsa AAAAB3Other other@host", key), lines())
        // The same key on a last line without a newline is found too.
        authorized.writeText("ssh-rsa AAAAB3Other other@host\n$key")
        install()
        assertEquals(listOf("ssh-rsa AAAAB3Other other@host", key), lines())
    }

    @Test
    fun `loose permissions are made private`() {
        ssh.mkdirs()
        authorized.writeText("")
        Files.setPosixFilePermissions(ssh.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
        Files.setPosixFilePermissions(authorized.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"))
        install()
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(ssh.toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(authorized.toPath())))
    }

    @Test
    fun `a folder that cannot be made fails with what sh said`() {
        File(home, ".ssh").writeText("not a folder")
        val (status, err) = install()
        assertTrue(status != 0)
        val failure = commandFailure(status, err)
        assertTrue(failure!!.detail, failure.detail.isNotBlank())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a key of two lines is refused`() {
        keyInstallInput("$key\nssh-ed25519 AAAAevil")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a line without a key is refused`() {
        keyInstallInput("ssh-ed25519")
    }

    @Test
    fun `the command's ending maps to a failure or none`() {
        assertNull(commandFailure(0, "warning: something\n"))
        assertEquals(KeyInstallResult.CommandFailed("mkdir: cannot create directory: Read-only file system"), commandFailure(1, "\n  mkdir: cannot create directory: Read-only file system\nmore\n"))
        assertEquals(KeyInstallResult.CommandFailed("exit status 2"), commandFailure(2, ""))
        assertEquals(KeyInstallResult.CommandFailed("no exit status"), commandFailure(null, " "))
    }

    @Test
    fun `each final result has its text, the questions none`() {
        assertEquals(KeyInstallText(R.string.key_install_done, emptyList(), ok = true), keyInstallText(KeyInstallResult.Installed, "root"))
        assertEquals(KeyInstallText(R.string.key_install_refused, listOf("root"), ok = false), keyInstallText(KeyInstallResult.KeyRefused, "root"))
        assertEquals(R.string.key_install_command_failed, keyInstallText(KeyInstallResult.CommandFailed("x"), "root")?.res)
        assertEquals(R.string.key_install_connection_failed, keyInstallText(KeyInstallResult.ConnectionFailed("x"), "root")?.res)
        assertEquals(KeyInstallText(R.string.key_install_done_password_kept, listOf("x"), ok = true), keyInstallText(KeyInstallResult.InstalledPasswordKept("x"), "root"))
        assertEquals(R.string.key_install_save_failed, keyInstallText(KeyInstallResult.SaveFailed("x"), "root")?.res)
        assertNull(keyInstallText(KeyInstallResult.PasswordNeeded(LoginRefusal.PASSWORD_TYPED), "root"))
        assertNull(keyInstallText(KeyInstallResult.HostKey(HostKeyCheck.Unknown, PinnedHostKey("ssh-ed25519", "SHA256:x")), "root"))
    }
}
