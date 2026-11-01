package ru.adsrv.frpjump.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android ships a stripped BouncyCastle under the name "BC" without
 * ed25519 or curve25519; sshj asks for "BC" by name. Swap in the full
 * provider under that name, appended at the end so nothing else (TLS to the
 * server, the Keystore) changes which provider it gets. Between the
 * removal and the addition there is no "BC" at all, so a TLS handshake
 * then fails ("BKS not found"): call it before anything can connect.
 */
fun installFullBouncyCastle() {
    val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
    if (current is BouncyCastleProvider) return
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.addProvider(BouncyCastleProvider())
}
