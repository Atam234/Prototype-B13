package com.zeroinstall.relay.hotspot

/** Wi-Fi security type, with the token used in the standard `WIFI:` QR payload. */
enum class WifiSecurity(val qrToken: String, val label: String) {
    OPEN("nopass", "Open"),
    WPA("WPA", "WPA2/WPA3"),
    SAE("SAE", "WPA3 (SAE)"),
}

enum class HotspotMode { LOCAL_ONLY, MANUAL }

data class HotspotInfo(
    val ssid: String,
    val passphrase: String,
    val security: WifiSecurity,
    val mode: HotspotMode,
)

/** Builds `WIFI:T:WPA;S:<SSID>;P:<PASS>;;` payloads understood by Android/iOS camera apps. */
object WifiQr {
    private const val SPECIAL = "\\;,:\""

    fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            if (c in SPECIAL) append('\\')
            append(c)
        }
    }

    fun build(ssid: String, passphrase: String, security: WifiSecurity): String =
        if (security == WifiSecurity.OPEN || passphrase.isEmpty()) {
            "WIFI:T:nopass;S:${escape(ssid)};;"
        } else {
            "WIFI:T:${security.qrToken};S:${escape(ssid)};P:${escape(passphrase)};;"
        }

    fun build(info: HotspotInfo): String = build(info.ssid, info.passphrase, info.security)
}
