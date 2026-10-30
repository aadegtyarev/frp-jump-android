package ru.adsrv.frpjump.store

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

class PermanentLossTest {
    @Test
    fun lostKeyOrDataMeansEnrollAgain() {
        assertTrue(isPermanentLoss(KeyMissingException()))
        assertTrue(isPermanentLoss(AEADBadTagException()))
        assertTrue(isPermanentLoss(SerializationException("bad json")))
        assertTrue(isPermanentLoss(IllegalArgumentException("unknown sealed format")))
        assertTrue(isPermanentLoss(ProviderException("wrapped", AEADBadTagException())))
    }

    @Test
    fun anythingElseIsWorthARetry() {
        assertFalse(isPermanentLoss(IOException("disk")))
        assertFalse(isPermanentLoss(KeyStoreException("keystore daemon busy")))
        assertFalse(isPermanentLoss(ProviderException("transient")))
        assertFalse(isPermanentLoss(IllegalStateException("whatever")))
    }
}
