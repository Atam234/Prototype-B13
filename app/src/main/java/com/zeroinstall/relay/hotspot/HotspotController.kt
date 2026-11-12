package com.zeroinstall.relay.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Wraps [WifiManager.startLocalOnlyHotspot]. The returned reservation must be held for as long
 * as the hotspot should stay up, which is why this lives inside the foreground service.
 */
class HotspotController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onHotspotStarted(info: HotspotInfo)
        fun onHotspotStopped()
        fun onHotspotFailed(reason: String)
    }

    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var requested = false

    val isActive: Boolean get() = reservation != null

    @SuppressLint("MissingPermission") // Permissions are requested by MainActivity before starting.
    fun start() {
        if (reservation != null || requested) return
        val manager = wifiManager ?: run {
            listener.onHotspotFailed("This device has no Wi-Fi manager.")
            return
        }
        requested = true
        try {
            manager.startLocalOnlyHotspot(callback, mainHandler)
        } catch (e: SecurityException) {
            requested = false
            listener.onHotspotFailed("Permission denied by the system: ${e.message}")
        } catch (e: IllegalStateException) {
            requested = false
            listener.onHotspotFailed("A hotspot request is already active: ${e.message}")
        }
    }

    fun stop() {
        requested = false
        reservation?.close()
        reservation = null
    }

    private val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
            if (!requested) {
                // stop() was called while the request was in flight.
                res.close()
                return
            }
            reservation = res
            val info = extractInfo(res)
            if (info.ssid.isBlank()) {
                listener.onHotspotFailed("Hotspot started but the system did not expose its SSID.")
            } else {
                listener.onHotspotStarted(info)
            }
        }

        override fun onStopped() {
            reservation = null
            requested = false
            listener.onHotspotStopped()
        }

        override fun onFailed(reason: Int) {
            reservation = null
            requested = false
            listener.onHotspotFailed(describeFailure(reason))
        }
    }

    private fun extractInfo(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                config.wifiSsid?.bytes?.toString(Charsets.UTF_8)
            } else {
                @Suppress("DEPRECATION")
                config.ssid
            }
            val security = when (config.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> WifiSecurity.OPEN
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> WifiSecurity.SAE
                else -> WifiSecurity.WPA
            }
            return HotspotInfo(
                ssid = ssid.orEmpty().removeSurrounding("\""),
                passphrase = config.passphrase.orEmpty(),
                security = security,
                mode = HotspotMode.LOCAL_ONLY,
            )
        }
        @Suppress("DEPRECATION")
        val config = res.wifiConfiguration
        @Suppress("DEPRECATION")
        val pass = config?.preSharedKey.orEmpty().removeSurrounding("\"")
        @Suppress("DEPRECATION")
        val ssid = config?.SSID.orEmpty().removeSurrounding("\"")
        return HotspotInfo(
            ssid = ssid,
            passphrase = pass,
            security = if (pass.isEmpty()) WifiSecurity.OPEN else WifiSecurity.WPA,
            mode = HotspotMode.LOCAL_ONLY,
        )
    }

    companion object {
        fun describeFailure(reason: Int): String = when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                "No Wi-Fi channel available for the hotspot."
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                "Incompatible Wi-Fi mode: turn off Portable Hotspot/tethering, or use manual hotspot mode."
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                "Tethering is disallowed on this device (carrier or admin policy). Use manual hotspot mode."
            else -> "The system could not start the local hotspot (code $reason). Use manual hotspot mode."
        }
    }
}
