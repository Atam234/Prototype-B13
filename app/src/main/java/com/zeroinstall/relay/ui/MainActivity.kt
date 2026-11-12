package com.zeroinstall.relay.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.zeroinstall.relay.service.RelayForegroundService
import com.zeroinstall.relay.ui.theme.ZeroInstallRelayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pendingAction: (() -> Unit)? = null
    private var pendingCritical: List<String> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val action = pendingAction
            val critical = pendingCritical
            pendingAction = null
            pendingCritical = emptyList()
            val denied = critical.filterNot(::isGranted)
            if (denied.isEmpty()) {
                action?.invoke()
            } else {
                viewModel.showNotice(
                    "Permission needed to create the hotspot: " + denied.joinToString { it.substringAfterLast('.') } +
                        ". Grant it in App settings or use manual hotspot mode."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZeroInstallRelayTheme {
                RelayScreen(
                    viewModel = viewModel,
                    onStartLocalHotspot = ::startLocalHotspotRelay,
                    onStartManualHotspot = ::startManualHotspotRelay,
                    onStopRelay = { RelayForegroundService.stop(this) },
                    onOpenHotspotSettings = ::openHotspotSettings,
                )
            }
        }
    }

    private fun startLocalHotspotRelay() {
        val critical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        withPermissions(critical) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !isLocationEnabled()) {
                viewModel.showNotice("Turn on Location: Android ${Build.VERSION.RELEASE} requires it for LocalOnlyHotspot")
                safeStartActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                RelayForegroundService.startLocalOnly(this)
            }
        }
    }

    private fun startManualHotspotRelay(ssid: String, passphrase: String) {
        if (ssid.isBlank()) {
            viewModel.showNotice("Enter your Portable Hotspot's network name (SSID)")
            return
        }
        if (passphrase.isNotEmpty() && passphrase.length < 8) {
            viewModel.showNotice("WPA hotspot passwords are at least 8 characters")
            return
        }
        withPermissions(emptyList()) { RelayForegroundService.startManual(this, ssid.trim(), passphrase) }
    }

    /** Requests [critical] (+ notifications, which are optional) and runs [action] once critical ones are granted. */
    private fun withPermissions(critical: List<String>, action: () -> Unit) {
        val optional = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
        val missing = (critical + optional).filterNot(::isGranted)
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingAction = action
        pendingCritical = critical
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun openHotspotSettings() {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        if (!safeStartActivity(tether)) safeStartActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun safeStartActivity(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
