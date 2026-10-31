package ru.adsrv.frpjump.direct

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * The network id tunnels go by, from the raw ids the system reports: a
 * new id counts once it has held for [settleMs] (a switch reports a few
 * passing states: capabilities and links arrive one by one), and losing
 * the network keeps the last id (without a network nothing goes anywhere,
 * and a kept device must not turn direct meanwhile). The first id counts
 * at once.
 */
class SettledNetworkId(private val settleMs: Long = SETTLE_MS) {
    var current: String? = null
        private set
    private var pending: String? = null
    private var pendingSince = 0L

    /** A raw id seen at [now]; returns how long to wait before [settle], or null when nothing is pending. */
    @Synchronized
    fun observe(id: String?, now: Long): Long? {
        when {
            id == null || id == current -> pending = null
            current == null -> {
                current = id
                pending = null
            }
            id != pending -> {
                pending = id
                pendingSince = now
            }
        }
        return settle(now)
    }

    /** Takes a pending id that has held long enough; returns how much longer to wait, or null. */
    @Synchronized
    fun settle(now: Long): Long? {
        val next = pending ?: return null
        val left = settleMs - (now - pendingSince)
        if (left > 0) return left
        current = next
        pending = null
        return null
    }

    companion object {
        const val SETTLE_MS = 1_000L
    }
}

/**
 * The default network's [networkIdentity], settled ([SettledNetworkId]).
 * Callbacks of a network that is no longer the default one are ignored.
 * Needs only ACCESS_NETWORK_STATE. [start] reads the current network at
 * once (binder calls: off the main thread) and then follows it.
 */
class NetworkWatcher(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val settled = SettledNetworkId()
    private val handler = Handler(Looper.getMainLooper())
    private val _id = MutableStateFlow<String?>(null)
    val id: StateFlow<String?> = _id

    // Only touched on the ConnectivityThread (and once in start, before the callback exists).
    private var network: Network? = null
    private var capabilities: NetworkCapabilities? = null
    private var link: LinkProperties? = null

    fun start() {
        try {
            connectivity.activeNetwork?.let { active ->
                network = active
                capabilities = connectivity.getNetworkCapabilities(active)
                link = connectivity.getLinkProperties(active)
                publish()
            }
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    if (n == network) return
                    network = n
                    capabilities = null
                    link = null
                }

                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    if (n != network) return
                    capabilities = caps
                    publish()
                }

                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                    if (n != network) return
                    link = lp
                    publish()
                }

                override fun onLost(n: Network) {
                    if (n != network) return
                    network = null
                    capabilities = null
                    link = null
                    observe(null)
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "no network callback: ${e.message}")
        }
    }

    private fun publish() {
        val caps = capabilities ?: return
        val lp = link ?: return
        observe(identityOf(caps, lp))
    }

    private fun observe(raw: String?) {
        val wait = settled.observe(raw, SystemClock.elapsedRealtime())
        _id.value = settled.current
        if (wait != null) handler.postDelayed(::settle, wait)
    }

    private fun settle() {
        val wait = settled.settle(SystemClock.elapsedRealtime())
        _id.value = settled.current
        if (wait != null) handler.postDelayed(::settle, wait)
    }

    private fun identityOf(caps: NetworkCapabilities, lp: LinkProperties): String {
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            else -> NetworkKind.OTHER
        }
        val gateways = lp.routes.filter { it.hasGateway() }.mapNotNull { it.gateway?.hostAddress }.toSet()
        val dns = lp.dnsServers.mapNotNull { it.hostAddress }.toSet()
        val subnets = lp.linkAddresses.filter { it.address is Inet4Address }.map { a ->
            val bits = a.prefixLength
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            val value = a.address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) } and mask
            "%d.%d.%d.%d/%d".format(value ushr 24 and 0xff, value ushr 16 and 0xff, value ushr 8 and 0xff, value and 0xff, bits)
        }
        val privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lp.privateDnsServerName else null
        return networkIdentity(kind, gateways, dns + listOfNotNull(privateDns), lp.domains, subnets)
    }

    private companion object {
        const val TAG = "NetworkWatcher"
    }
}
