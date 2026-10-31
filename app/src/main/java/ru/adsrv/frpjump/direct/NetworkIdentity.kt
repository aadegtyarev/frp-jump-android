package ru.adsrv.frpjump.direct

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.security.MessageDigest

/** The kind of network the phone's traffic goes out on. */
enum class NetworkKind(val label: String) { MOBILE("mobile"), WIFI("wifi"), ETHERNET("ethernet"), VPN("vpn"), OTHER("other") }

/**
 * What "the current network" means for "Keep through the server", without
 * the location permission the Wi-Fi name (SSID, BSSID) needs:
 *
 * - mobile data is one network ("mobile"): carriers' NAT behaves the same
 *   wherever the phone is, and its addresses change all the time;
 * - anything else is its kind plus a hash of what the router hands out:
 *   the gateways (an IPv6 router's link-local address usually carries its
 *   MAC), the DNS servers, the search domains and the IPv4 subnets. Global
 *   IPv6 addresses are left out: many providers rotate those prefixes.
 *
 * Two Wi-Fi networks with the same plain setup (192.168.1.0/24, gateway and
 * DNS .1, no IPv6) look the same; a keep made on one then applies to the
 * other, and "Retry direct" on the card undoes it. That is the price of not
 * asking for location.
 */
fun networkIdentity(kind: NetworkKind, gateways: Collection<String>, dns: Collection<String>, domains: String?, subnets: Collection<String>): String {
    if (kind == NetworkKind.MOBILE) return kind.label
    val facts = buildList {
        gateways.sorted().forEach { add("gw=$it") }
        dns.sorted().forEach { add("dns=$it") }
        domains?.takeIf { it.isNotBlank() }?.let { add("domains=${it.trim().lowercase()}") }
        subnets.sorted().forEach { add("net=$it") }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(facts.joinToString("\n").toByteArray(Charsets.UTF_8))
    return kind.label + ":" + digest.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * The default network's [networkIdentity], kept current by a default
 * network callback; null while there is none. Needs only
 * ACCESS_NETWORK_STATE.
 */
class NetworkWatcher(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _id = MutableStateFlow<String?>(null)
    val id: StateFlow<String?> = _id

    private var capabilities: NetworkCapabilities? = null
    private var link: LinkProperties? = null

    fun start() {
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    capabilities = caps
                    publish()
                }

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    link = lp
                    publish()
                }

                override fun onLost(network: Network) {
                    capabilities = null
                    link = null
                    _id.value = null
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "no network callback: ${e.message}")
        }
    }

    // Callbacks come one at a time on the ConnectivityThread.
    private fun publish() {
        val caps = capabilities ?: return
        val lp = link ?: return
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            else -> NetworkKind.OTHER
        }
        val gateways = lp.routes.filter { it.hasGateway() }.mapNotNull { it.gateway?.hostAddress }.toSet()
        val dns = lp.dnsServers.mapNotNull { it.hostAddress }.toSet()
        val subnets = lp.linkAddresses.filter { it.address is Inet4Address }.mapNotNull { a ->
            val bytes = a.address.address
            val bits = a.prefixLength
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            val value = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) } and mask
            "%d.%d.%d.%d/%d".format(value ushr 24 and 0xff, value ushr 16 and 0xff, value ushr 8 and 0xff, value and 0xff, bits)
        }
        val domains = lp.domains
        val privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lp.privateDnsServerName else null
        _id.value = networkIdentity(kind, gateways, dns + listOfNotNull(privateDns), domains, subnets)
    }

    private companion object {
        const val TAG = "NetworkWatcher"
    }
}
