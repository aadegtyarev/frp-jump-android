package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * К4: the export file is what `ssh-keygen -p` writes (bcrypt, aes256-ctr) and reads back to the same key. All keys are
 * made here, in the tests; nothing is committed as a file.
 */
class SshKeyExportTest {
    // The armor lines are built from parts, so no literal in this file looks like a key to a secret scanner.
    private val label = "OPENSSH PRIVATE KEY"
    private val BEGIN = "-----BEGIN $label-----"
    private val END = "-----END $label-----"
    private val BEGIN_PKCS8 = "-----BEGIN PRIVATE" + " KEY-----"
    private val END_PKCS8 = "-----END PRIVATE" + " KEY-----"

    private val pass = "correct horse battery"
    private fun phrase() = pass.toCharArray()

    private fun export(pair: SshKeyPair = generateEd25519("c"), p: CharArray = phrase(), scratch: Scratch = Scratch()): ByteArray =
        SshKeyExport.encryptPhoneKey(pair.privateKey, "frp-jump@test", p, scratch = scratch)

    private fun blobOf(line: String) = blobOfPublicKeyLine(line)!!

    /** The 32-byte seed in an unencrypted OpenSSH ed25519 key text. */
    private fun seedOf(text: String): ByteArray {
        val body = Base64.getMimeDecoder().decode(text.lines().filter { !it.startsWith("-----") }.joinToString(""))
        return (org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(body) as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded
    }

    private fun refused(problem: KeyFileProblem, type: String? = null, block: () -> Unit) {
        try {
            block()
            fail("expected $problem")
        } catch (e: KeyFileException) {
            assertEquals(problem, e.problem)
            if (type != null) assertEquals(type, e.keyType)
        }
    }

    // The round-trip ------------------------------------------------------------------------------------------------

    @Test fun exportThenRestoreGivesTheSameKey() {
        val pair = generateEd25519("c")
        val file = export(pair)
        val back = SshKeyExport.decryptEd25519(file, phrase(), "frp-jump@new")
        assertArrayEquals(seedOf(pair.privateKey), seedOf(back.privateKey))
        assertArrayEquals(blobOf(pair.publicLine), blobOf(back.publicLine))
        assertTrue(back.publicLine.endsWith(" frp-jump@new"))
    }

    @Test fun theFileIsAnEncryptedOpenSshKeyWithBcryptAes256CtrSaltAndRounds() {
        val file = export()
        val text = String(file, Charsets.US_ASCII)
        assertTrue(text.startsWith("$BEGIN\n"))
        assertTrue(text.endsWith("$END\n"))
        assertTrue(text.lines().all { it.length <= 70 || it.startsWith("-----") })
        val blob = Base64.getMimeDecoder().decode(text.lines().filter { !it.startsWith("-----") }.joinToString(""))
        val r = Wire(blob)
        assertEquals("openssh-key-v1\u0000", String(r.take(15)))
        assertEquals("aes256-ctr", String(r.string()))
        assertEquals("bcrypt", String(r.string()))
        val options = Wire(r.string())
        assertEquals(16, options.string().size)
        assertEquals(16, options.int())
        assertEquals(1, r.int())
        r.string() // public blob
        assertEquals("the encrypted part is whole cipher blocks", 0, r.string().size % 16)
    }

    @Test fun twoExportsOfTheSameKeyDiffer() {
        val pair = generateEd25519("c")
        assertFalse(export(pair).contentEquals(export(pair)))
    }

    @Test fun theFileHoldsNeitherThePassphraseNorTheSeedInTheClear() {
        val pair = generateEd25519("c")
        val plain = Base64.getMimeDecoder().decode(pair.privateKey.lines().filter { !it.startsWith("-----") }.joinToString(""))
        val seed = (org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(plain) as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded
        val file = export(pair)
        val blob = Base64.getMimeDecoder().decode(String(file).lines().filter { !it.startsWith("-----") }.joinToString(""))
        assertFalse(String(file, Charsets.ISO_8859_1).contains(pass))
        assertTrue(blob.indexOfSubarray(seed) < 0)
        assertTrue("sanity: the search does find it in the unencrypted key", plain.indexOfSubarray(seed) >= 0)
    }

    private fun ByteArray.indexOfSubarray(part: ByteArray): Int =
        (0..size - part.size).firstOrNull { i -> part.indices.all { this[i + it] == part[it] } } ?: -1

    // Compatibility with independent readers -------------------------------------------------------------------------

    @Test fun sshjReadsTheFileWithThePassphraseAndFindsTheSameKey() {
        val pair = generateEd25519("c")
        val file = export(pair)
        val provider = SSHClient().loadKeys(String(file), null, PasswordUtils.createOneOff(phrase()))
        assertArrayEquals(blobOf(pair.publicLine), Buffer.PlainBuffer().putPublicKey(provider.public).compactData)
    }

    @Test fun sshjRefusesTheFileWithAWrongPassphrase() {
        try {
            SSHClient().loadKeys(String(export()), null, PasswordUtils.createOneOff("not the one".toCharArray())).public
            fail("a wrong passphrase must not load")
        } catch (e: Exception) {
            // refused
        }
    }

    @Test fun sshKeygenReadsTheFileAndFindsTheSamePublicKey() {
        assumeTrue("ssh-keygen is not installed", haveSshKeygen())
        val pair = generateEd25519("c")
        withTemp { dir ->
            val f = File(dir, "k").apply { writeBytes(export(pair)) }
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"))
            val out = run("ssh-keygen", "-y", "-P", pass, "-f", f.path)
            assertArrayEquals(blobOf(pair.publicLine), blobOf(out))
            // and it is encrypted for ssh-keygen too: no passphrase, no key
            val wrong = run("ssh-keygen", "-y", "-P", "nope-nope", "-f", f.path)
            assertTrue(wrong, wrong.contains("incorrect passphrase", ignoreCase = true))
        }
    }

    @Test fun aFileWrittenBySshKeygenWithAPassphraseIsRestored() {
        assumeTrue("ssh-keygen is not installed", haveSshKeygen())
        withTemp { dir ->
            val f = File(dir, "k")
            run("ssh-keygen", "-q", "-t", "ed25519", "-N", pass, "-C", "other", "-f", f.path)
            val back = SshKeyExport.decryptEd25519(f.readBytes(), phrase(), "frp-jump@new")
            assertArrayEquals(blobOf(File(dir, "k.pub").readText()), blobOf(back.publicLine))
            // and re-encrypted with `ssh-keygen -p`, still the same
            run("ssh-keygen", "-p", "-P", pass, "-N", "another phrase", "-f", f.path)
            val again = SshKeyExport.decryptEd25519(f.readBytes(), "another phrase".toCharArray(), "x")
            assertArrayEquals(blobOf(back.publicLine), blobOf(again.publicLine))
        }
    }

    @Test fun anRsaKeyFromSshKeygenIsRefusedAsNotEd25519BeforeAnyPassphrase() {
        assumeTrue("ssh-keygen is not installed", haveSshKeygen())
        withTemp { dir ->
            val f = File(dir, "k")
            run("ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-N", pass, "-f", f.path)
            refused(KeyFileProblem.NOT_ED25519, "ssh-rsa") { SshKeyExport.inspect(f.readBytes()) }
            refused(KeyFileProblem.NOT_ED25519, "ssh-rsa") { SshKeyExport.decryptEd25519(f.readBytes(), phrase(), "x") }
        }
    }

    // Refusals: each leaves nothing behind ---------------------------------------------------------------------------

    @Test fun aWrongPassphraseIsRefusedAsWrong() {
        val file = export()
        refused(KeyFileProblem.WRONG_PASSPHRASE) { SshKeyExport.decryptEd25519(file, "wrong wrong".toCharArray(), "x") }
        refused(KeyFileProblem.WRONG_PASSPHRASE) { SshKeyExport.decryptEd25519(file, CharArray(0), "x") }
    }

    @Test fun aNonEd25519KeyIsRefusedWithItsTypeBeforeAPassphrase() {
        // An OpenSSH container whose public half says ssh-rsa (the rest is not needed: the type is read first).
        val file = container(publicType = "ssh-rsa")
        refused(KeyFileProblem.NOT_ED25519, "ssh-rsa") { SshKeyExport.inspect(file) }
        refused(KeyFileProblem.NOT_ED25519, "ssh-rsa") { SshKeyExport.decryptEd25519(file, phrase(), "x") }
        val ecdsa = container(publicType = "ecdsa-sha2-nistp256")
        refused(KeyFileProblem.NOT_ED25519, "ecdsa-sha2-nistp256") { SshKeyExport.inspect(ecdsa) }
    }

    @Test fun aPemKeyOfAnotherTypeIsRefusedAsNotEd25519() {
        val pem = pemRsa()
        refused(KeyFileProblem.NOT_ED25519, "ssh-rsa") { SshKeyExport.inspect(pem.toByteArray()) }
        refused(KeyFileProblem.NOT_ED25519, null) {
            SshKeyExport.inspect("$BEGIN_PKCS8\nAAAA\n$END_PKCS8\n".toByteArray())
        }
    }

    @Test fun damagedFilesAreRefusedAsDamaged() {
        val file = export()
        refused(KeyFileProblem.DAMAGED) { SshKeyExport.inspect("hello".toByteArray()) }
        refused(KeyFileProblem.DAMAGED) { SshKeyExport.inspect(ByteArray(0)) }
        refused(KeyFileProblem.DAMAGED) { SshKeyExport.inspect(file.copyOf(file.size / 2)) }
        // cut inside the base64 body but keeping valid armor lines
        val text = String(file)
        val lines = text.lines()
        val cut = (lines.take(2) + listOf(END)).joinToString("\n")
        refused(KeyFileProblem.DAMAGED) { SshKeyExport.decryptEd25519(cut.toByteArray(), phrase(), "x") }
        // a flipped byte in the encrypted private part, past the check ints: the passphrase is right, the key is not whole
        val blob = Base64.getMimeDecoder().decode(lines.filter { !it.startsWith("-----") }.joinToString(""))
        val tampered = blob.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 0x55).toByte() }
        val damaged = "$BEGIN\n" + Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(tampered) +
            "\n$END\n"
        refused(KeyFileProblem.DAMAGED) { SshKeyExport.decryptEd25519(damaged.toByteArray(), phrase(), "x") }
        // a flip in the public half: the file's two halves disagree
        val pubFlip = blob.copyOf().also { it[99] = (it[99].toInt() xor 0x77).toByte() }
        val inconsistent = "$BEGIN\n" + Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(pubFlip) +
            "\n$END\n"
        refused(KeyFileProblem.DAMAGED) { SshKeyExport.decryptEd25519(inconsistent.toByteArray(), phrase(), "x") }
    }

    @Test fun otherCiphersAndKdfSettingsAreUnsupportedAndHugeRoundsAreNotRun() {
        refused(KeyFileProblem.UNSUPPORTED) { SshKeyExport.inspect(container(cipher = "aes128-ctr")) }
        refused(KeyFileProblem.UNSUPPORTED) { SshKeyExport.inspect(container(cipher = "chacha20-poly1305@openssh.com")) }
        refused(KeyFileProblem.UNSUPPORTED) { SshKeyExport.inspect(container(rounds = 1_000_000)) }
        refused(KeyFileProblem.UNSUPPORTED) { SshKeyExport.inspect(container(keys = 2)) }
    }

    @Test fun anUnencryptedOpenSshEd25519KeyNeedsNoPassphrase() {
        val pair = generateEd25519("c")
        assertFalse(SshKeyExport.inspect(pair.privateKey.toByteArray()).encrypted)
        val back = SshKeyExport.decryptEd25519(pair.privateKey.toByteArray(), CharArray(0), "frp-jump@new")
        assertArrayEquals(seedOf(pair.privateKey), seedOf(back.privateKey))
        assertTrue(SshKeyExport.inspect(export()).encrypted)
    }

    // Zeroing ---------------------------------------------------------------------------------------------------------

    @Test fun thePassphraseArrayIsZeroedAfterExportRestoreAndAFailure() {
        val a = phrase()
        val file = export(p = a)
        assertTrue(a.all { it == '\u0000' })
        val b = phrase()
        SshKeyExport.decryptEd25519(file, b, "x")
        assertTrue(b.all { it == '\u0000' })
        val c = "wrong wrong".toCharArray()
        refused(KeyFileProblem.WRONG_PASSPHRASE) { SshKeyExport.decryptEd25519(file, c, "x") }
        assertTrue(c.all { it == '\u0000' })
        val d = phrase()
        refused(KeyFileProblem.DAMAGED) { SshKeyExport.decryptEd25519("junk".toByteArray(), d, "x") }
        assertTrue(d.all { it == '\u0000' })
    }

    @Test fun everySecretArrayOfAnExportAndARestoreIsZeroedWhenTheyEnd() {
        val pair = generateEd25519("c")
        val out = Scratch()
        val file = export(pair, scratch = out)
        assertSecretsGone(out, expectedSizes = listOf(32, 16, 144, 48))
        val back = Scratch()
        SshKeyExport.decryptEd25519(file, phrase(), "x", back)
        assertSecretsGone(back, expectedSizes = listOf(32, 16, 144, 48))
        val failed = Scratch()
        refused(KeyFileProblem.WRONG_PASSPHRASE) { SshKeyExport.decryptEd25519(file, "wrong wrong".toCharArray(), "x", failed) }
        assertSecretsGone(failed, expectedSizes = listOf(32, 16, 48))
    }

    private fun assertSecretsGone(scratch: Scratch, expectedSizes: List<Int>) {
        val sizes = scratch.tracked.map { it.size }
        expectedSizes.forEach { assertTrue("no secret array of $it bytes among $sizes", it in sizes) }
        scratch.tracked.forEach { a -> assertTrue("an array of ${a.size} bytes was left with data", a.all { it == 0.toByte() }) }
    }

    @Test fun scratchZeroesEverythingItHolds() {
        val s = Scratch()
        val a = s.bytes(4).also { it.fill(7) }
        val b = s.keep(byteArrayOf(1, 2, 3))
        s.close()
        assertTrue(a.all { it == 0.toByte() } && b.all { it == 0.toByte() })
    }

    // helpers ---------------------------------------------------------------------------------------------------------

    private class Wire(private val b: ByteArray) {
        var pos = 0
        fun take(n: Int) = b.copyOfRange(pos, pos + n).also { pos += n }
        fun int() = (0 until 4).fold(0) { v, _ -> (v shl 8) or (b[pos++].toInt() and 0xff) }
        fun string() = take(int())
    }

    /** A header with the given settings and a filler section: for the refusals that are decided before decryption. */
    private fun container(
        cipher: String = "aes256-ctr",
        kdf: String = "bcrypt",
        rounds: Int = 16,
        keys: Int = 1,
        publicType: String = "ssh-ed25519",
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        fun str(b: ByteArray) { d.writeInt(b.size); d.write(b) }
        d.write("openssh-key-v1".toByteArray()); d.write(0)
        str(cipher.toByteArray()); str(kdf.toByteArray())
        val options = java.io.ByteArrayOutputStream().also { o -> java.io.DataOutputStream(o).apply { writeInt(16); write(ByteArray(16)); writeInt(rounds) } }
        str(options.toByteArray())
        d.writeInt(keys)
        val pub = java.io.ByteArrayOutputStream().also { o -> java.io.DataOutputStream(o).apply { writeInt(publicType.length); write(publicType.toByteArray()); writeInt(32); write(ByteArray(32)) } }
        str(pub.toByteArray())
        str(ByteArray(160))
        val body = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(out.toByteArray())
        return "$BEGIN\n$body\n$END\n".toByteArray()
    }

    private fun pemRsa(): String {
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val w = java.io.StringWriter()
        org.bouncycastle.util.io.pem.PemWriter(w).use { it.writeObject(org.bouncycastle.util.io.pem.PemObject("RSA PRIVATE KEY", pair.private.encoded)) }
        return w.toString()
    }

    private fun haveSshKeygen(): Boolean = try {
        ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor(10, TimeUnit.SECONDS)
    } catch (e: Exception) {
        false
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(60, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun withTemp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("k4-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
