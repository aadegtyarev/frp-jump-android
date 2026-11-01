package net.schmizz.sshj.transport

import net.schmizz.sshj.DefaultConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SshSession pins host keys by key type (`ssh-rsa` for any RSA key) and
 * hands that name to sshj as the preferred host key algorithm. sshj
 * (0.41.2) maps it to the key type, so an RSA pin still offers the SHA-2
 * signatures first and only falls back to SHA-1 `ssh-rsa` last. Here, in
 * sshj's package, because [Proposal] is package-private.
 */
class PinnedRsaProposalTest {
    @Test
    fun `an ssh-rsa pin prefers rsa-sha2-512 and rsa-sha2-256 over ssh-rsa`() {
        val algorithms = Proposal(DefaultConfig(), listOf("ssh-rsa"), false).hostKeyAlgorithms
        val rsa = algorithms.filter { it == "rsa-sha2-512" || it == "rsa-sha2-256" || it == "ssh-rsa" }
        assertEquals(listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"), rsa)
        assertEquals(listOf("rsa-sha2-512", "rsa-sha2-256"), algorithms.take(2))
    }
}
