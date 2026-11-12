package com.zeroinstall.relay.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    data class LocalAddress(val interfaceName: String, val address: String)

    // Cellular / VPN / virtual interfaces that never carry the hotspot subnet.
    private val excludedPrefixes = listOf(
        "rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "dummy", "ifb", "lo", "radio", "seth", "pdp", "ipsec",
    )

    /**
     * IPv4 addresses a Helper on our hotspot could reach. LocalOnlyHotspot uses a randomized
     * subnet on Android 11+ (e.g. 192.168.x.1), so we enumerate interfaces instead of assuming
     * 192.168.43.1 / 192.168.49.1. Soft-AP interfaces are sorted first.
     */
    fun hotspotCandidateAddresses(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface -> runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) }
            .filterNot { iface -> excludedPrefixes.any { iface.name.startsWith(it) } }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .mapNotNull { addr -> addr.hostAddress?.let { LocalAddress(iface.name, it) } }
            }
            .distinctBy { it.address }
            .sortedBy { interfaceRank(it.interfaceName) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun interfaceRank(name: String): Int = when {
        name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 0
        name.startsWith("wlan") -> 1
        name.startsWith("p2p") -> 2
        else -> 3
    }

    /** True when the default network has validated internet access (not just a local link). */
    fun hasValidatedInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
