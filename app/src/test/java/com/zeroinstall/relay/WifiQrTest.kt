package com.zeroinstall.relay

import com.zeroinstall.relay.hotspot.WifiQr
import com.zeroinstall.relay.hotspot.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiQrTest {

    @Test
    fun wpaPayload() {
        assertEquals(
            "WIFI:T:WPA;S:AndroidShare_1234;P:abcd1234;;",
            WifiQr.build("AndroidShare_1234", "abcd1234", WifiSecurity.WPA),
        )
    }

    @Test
    fun escapesSpecialCharacters() {
        assertEquals(
            "WIFI:T:WPA;S:My\\;Net\\:1;P:p\\\\a\\,s\\\"s;;",
            WifiQr.build("My;Net:1", "p\\a,s\"s", WifiSecurity.WPA),
        )
    }

    @Test
    fun openNetworkOmitsPassword() {
        assertEquals("WIFI:T:nopass;S:Open;;", WifiQr.build("Open", "", WifiSecurity.OPEN))
    }

    @Test
    fun saeToken() {
        assertEquals("WIFI:T:SAE;S:X;P:12345678;;", WifiQr.build("X", "12345678", WifiSecurity.SAE))
    }
}
