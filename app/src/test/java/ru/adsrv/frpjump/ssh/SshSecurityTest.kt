package ru.adsrv.frpjump.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.Security

class SshSecurityTest {
    @Test
    fun `the full provider takes the BC name and still offers the BKS key store TLS loads`() {
        installFullBouncyCastle()
        val bc = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        assertTrue(bc is BouncyCastleProvider)
        // The trust store lookup that failed while "BC" was missing.
        assertNotNull(KeyStore.getInstance("BKS", BouncyCastleProvider.PROVIDER_NAME))
        // A second call keeps it: nothing is removed again.
        installFullBouncyCastle()
        assertSame(bc, Security.getProvider(BouncyCastleProvider.PROVIDER_NAME))
    }
}
