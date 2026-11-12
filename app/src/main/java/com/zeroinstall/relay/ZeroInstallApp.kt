package com.zeroinstall.relay

import android.app.Application
import android.content.Context
import com.zeroinstall.relay.crypto.PayloadCrypto
import com.zeroinstall.relay.data.MessageRepository
import com.zeroinstall.relay.data.RelayDatabase
import com.zeroinstall.relay.data.RelaySettings
import com.zeroinstall.relay.net.CloudUploader
import com.zeroinstall.relay.service.RelayForegroundService
import com.zeroinstall.relay.sync.AutoSyncWorker
import kotlinx.serialization.json.Json

/** Minimal manual dependency graph shared by the UI, the foreground service and workers. */
class AppGraph(context: Context) {
    val database: RelayDatabase = RelayDatabase.build(context)
    val settings = RelaySettings(context)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    val repository = MessageRepository(database.messageDao(), PayloadCrypto())
    val uploader = CloudUploader(json)
}

class ZeroInstallApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        RelayForegroundService.createNotificationChannel(this)
        AutoSyncWorker.schedulePeriodic(this)
    }
}
