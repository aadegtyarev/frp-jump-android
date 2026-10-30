package ru.adsrv.frpjump.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyMaterialTest {
    @Test
    fun generatedKeyLoadsInSshjAndMatchesItsPublicLine() {
        val pair = generateEd25519("frp-jump@phone")
        assertTrue(pair.publicLine.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI"))
        assertTrue(pair.publicLine.endsWith(" frp-jump@phone"))
        val provider = SSHClient().loadKeys(pair.privateKey, null, null)
        val loaded = Buffer.PlainBuffer().putPublicKey(provider.public).compactData
        assertArrayEquals(blobOfPublicKeyLine(pair.publicLine), loaded)
        assertEquals("ssh-ed25519", provider.type.toString())
    }
}
