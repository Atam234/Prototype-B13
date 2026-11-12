package com.zeroinstall.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zeroinstall.relay.R
import com.zeroinstall.relay.ZeroInstallApp
import com.zeroinstall.relay.hotspot.HotspotController
import com.zeroinstall.relay.hotspot.HotspotInfo
import com.zeroinstall.relay.hotspot.HotspotMode
import com.zeroinstall.relay.hotspot.WifiSecurity
import com.zeroinstall.relay.net.NetworkUtils
import com.zeroinstall.relay.server.LocalRelayServer
import com.zeroinstall.relay.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service (type connectedDevice) that owns the LocalOnlyHotspot reservation and the
 * embedded Ktor server, plus partial wake + Wi-Fi locks, so relaying keeps working with the
 * screen off.
 */
class RelayForegroundService : Service(), HotspotController.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var server: LocalRelayServer
    private lateinit var hotspot: HotspotController
    private var addressJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        val graph = (application as ZeroInstallApp).graph
        server = LocalRelayServer(
            repository = graph.repository,
            portalLoader = {
                assets.open(LocalRelayServer.PORTAL_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            },
            cloudEndpoint = { graph.settings.cloudEndpoint },
        )
        hotspot = HotspotController(this, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { HotspotMode.valueOf(it) }.getOrNull() }
                    ?: HotspotMode.LOCAL_ONLY
                val manualInfo = if (mode == HotspotMode.MANUAL) {
                    val ssid = intent.getStringExtra(EXTRA_SSID).orEmpty()
                    val pass = intent.getStringExtra(EXTRA_PASSPHRASE).orEmpty()
                    HotspotInfo(ssid, pass, if (pass.isEmpty()) WifiSecurity.OPEN else WifiSecurity.WPA, HotspotMode.MANUAL)
                } else {
                    null
                }
                startRelay(mode, manualInfo)
            }
            else -> {
                // ACTION_STOP, or a null intent after process death: never leave a zombie relay running.
                shutdown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRelay(mode: HotspotMode, manualInfo: HotspotInfo?) {
        goForeground(getString(R.string.app_name), "Starting relay…")
        acquireLocks()
        val port = (application as ZeroInstallApp).graph.settings.serverPort
        RelayStateHolder.update {
            it.copy(phase = RelayPhase.STARTING, error = null, serverPort = port)
        }

        scope.launch {
            if (!server.isRunning) {
                // A previous instance may still be releasing the port; retry the bind briefly.
                var result = withContext(Dispatchers.IO) { runCatching { server.start(port) } }
                var attempt = 1
                while (result.isFailure && attempt < BIND_ATTEMPTS) {
                    delay(BIND_RETRY_DELAY_MS)
                    result = withContext(Dispatchers.IO) { runCatching { server.start(port) } }
                    attempt++
                }
                result.exceptionOrNull()?.let { error ->
                    val message = "Could not start local server on port $port: ${error.message}"
                    RelayStateHolder.log(message)
                    RelayStateHolder.update { it.copy(phase = RelayPhase.ERROR, serverRunning = false, error = message) }
                    updateNotification("Relay error", message)
                    return@launch
                }
                RelayStateHolder.log("Local relay server listening on 0.0.0.0:$port")
            }
            RelayStateHolder.update { it.copy(serverRunning = true) }
            startAddressPolling(port)

            when (mode) {
                HotspotMode.LOCAL_ONLY -> {
                    if (hotspot.isActive) {
                        RelayStateHolder.update { it.copy(phase = RelayPhase.ACTIVE) }
                    } else {
                        RelayStateHolder.log("Requesting LocalOnlyHotspot from the system…")
                        hotspot.start()
                    }
                }
                HotspotMode.MANUAL -> {
                    hotspot.stop()
                    requireNotNull(manualInfo)
                    RelayStateHolder.log("Manual hotspot mode: serving relay on the phone's Portable Hotspot")
                    onHotspotReady(manualInfo)
                }
            }
        }
    }

    override fun onHotspotStarted(info: HotspotInfo) {
        RelayStateHolder.log("Hotspot up: SSID \"${info.ssid}\" (${info.security.label})")
        onHotspotReady(info)
    }

    override fun onHotspotStopped() {
        val message = "The system stopped the local hotspot (another Wi-Fi/tethering mode may have started)."
        RelayStateHolder.log(message)
        RelayStateHolder.update { it.copy(phase = RelayPhase.ERROR, hotspot = null, error = message) }
        updateNotification("Hotspot stopped", "Local server still running. Restart or use manual hotspot mode.")
    }

    override fun onHotspotFailed(reason: String) {
        RelayStateHolder.log("Hotspot failed: $reason")
        RelayStateHolder.update { it.copy(phase = RelayPhase.ERROR, hotspot = null, error = reason) }
        updateNotification("Hotspot failed", "Local server still running. Enable Portable Hotspot and use manual mode.")
    }

    private fun onHotspotReady(info: HotspotInfo) {
        RelayStateHolder.update { it.copy(phase = RelayPhase.ACTIVE, hotspot = info, error = null) }
        refreshNotification()
    }

    /** The soft-AP interface usually gets its IPv4 address a moment after the hotspot starts. */
    private fun startAddressPolling(port: Int) {
        addressJob?.cancel()
        addressJob = scope.launch {
            while (isActive) {
                val urls = withContext(Dispatchers.IO) { NetworkUtils.hotspotCandidateAddresses() }
                    .map { "http://${it.address}:$port" }
                if (urls != RelayStateHolder.state.value.localUrls) {
                    RelayStateHolder.update { it.copy(localUrls = urls) }
                    if (urls.isNotEmpty()) RelayStateHolder.log("Relay portal reachable at ${urls.joinToString()}")
                    refreshNotification()
                }
                delay(if (urls.isEmpty()) 1_000 else 5_000)
            }
        }
    }

    private fun refreshNotification() {
        val state = RelayStateHolder.state.value
        val ssid = state.hotspot?.ssid
        val url = state.localUrls.firstOrNull()
        val title = if (ssid != null) "Relay hotspot: $ssid" else getString(R.string.app_name)
        val text = when {
            url != null -> "Helpers: join Wi-Fi, then open $url"
            ssid != null -> "Waiting for hotspot IP address…"
            else -> "Starting relay…"
        }
        updateNotification(title, text)
    }

    private fun shutdown() {
        addressJob?.cancel()
        addressJob = null
        if (::hotspot.isInitialized) hotspot.stop()
        if (::server.isInitialized && server.isRunning) {
            val s = server
            // Ktor's stop() blocks while draining connections; keep it off the main thread.
            Thread({ runCatching { s.stop() } }, "relay-server-stop").start()
            RelayStateHolder.log("Relay stopped")
        }
        releaseLocks()
        RelayStateHolder.reset()
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(title: String, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(title, text), type)
        inForeground = true
    }

    private fun updateNotification(title: String, text: String) {
        if (!inForeground) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(title, text)) }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RelayForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_relay_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZeroInstallRelay:server")?.apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "ZeroInstallRelay:wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    companion object {
        const val CHANNEL_ID = "relay_hotspot"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_WAKE_LOCK_MS = 4L * 60 * 60 * 1000
        private const val BIND_ATTEMPTS = 4
        private const val BIND_RETRY_DELAY_MS = 750L
        private const val ACTION_START = "com.zeroinstall.relay.action.START"
        private const val ACTION_STOP = "com.zeroinstall.relay.action.STOP"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SSID = "ssid"
        private const val EXTRA_PASSPHRASE = "passphrase"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_channel_description) }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        fun startLocalOnly(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, HotspotMode.LOCAL_ONLY.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startManual(context: Context, ssid: String, passphrase: String) {
            val intent = Intent(context, RelayForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, HotspotMode.MANUAL.name)
                .putExtra(EXTRA_SSID, ssid)
                .putExtra(EXTRA_PASSPHRASE, passphrase)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RelayForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
