package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SshFingerprintTest {
    // Generated with `ssh-keygen -t ed25519 -C test@example`; `ssh-keygen -lf` prints the fingerprints below.
    private val ed25519Line =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDiRh1/Wuo6vUtCVQ2KqhJeXJGHK3U5OU28jCT6LbDaA test@example"
    private val rsaLine = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQCvndXwZdY5MDWyWTpRsb02AYPs7sSI4uM00zctuYuqJK5X3zgarfdMmLeRxQOf" +
        "bJBXRj5CtYrzDSjnj3+HbDnjBqwCmMrqqOIYLz0QSewxOTh4hzyH4+BkVefYY1EisEXUh1DfE/Rtpvx61dsTRpfG3W5fZcnIMRrMNJEhvpcwew== r"

    @Test
    fun matchesSshKeygenForEd25519() {
        assertEquals("SHA256:c9GWFPbZxoNfOZKf9KkTtp5CeHzHMXEcOcHBZjOXk/8", sshFingerprint(blobOfPublicKeyLine(ed25519Line)!!))
    }

    @Test
    fun matchesSshKeygenForRsa() {
        assertEquals("SHA256:/xfapJWzsQEgdBsECu0MUFfRoWFvr5Vr0fBrCW4gOqc", sshFingerprint(blobOfPublicKeyLine(rsaLine)!!))
    }

    @Test
    fun buildsTheSameLineSshKeygenWrites() {
        val blob = blobOfPublicKeyLine(ed25519Line)!!
        val raw = blob.copyOfRange(blob.size - 32, blob.size)
        assertArrayEquals(blob, ed25519PublicBlob(raw))
        assertEquals(ed25519Line, publicKeyLine(ED25519, ed25519PublicBlob(raw), "test@example"))
    }

    @Test
    fun rejectsWhatIsNotAKeyLine() {
        assertNull(blobOfPublicKeyLine("ssh-ed25519"))
        assertNull(blobOfPublicKeyLine("ssh-ed25519 not*base64"))
    }
}
