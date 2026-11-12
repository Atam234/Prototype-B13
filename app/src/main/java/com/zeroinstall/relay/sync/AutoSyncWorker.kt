package com.zeroinstall.relay.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zeroinstall.relay.ZeroInstallApp
import com.zeroinstall.relay.net.NetworkUtils
import com.zeroinstall.relay.service.RelayStateHolder
import java.util.concurrent.TimeUnit

/**
 * Fallback path: when the Sender itself regains validated internet, upload every message that
 * has not been confirmed DELIVERED directly to the cloud endpoint.
 */
class AutoSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as ZeroInstallApp).graph
        if (!NetworkUtils.hasValidatedInternet(applicationContext)) {
            // Connected to something (e.g. a Wi-Fi without internet) but no validated route out.
            return Result.retry()
        }

        val candidates = graph.repository.undelivered()
        if (candidates.isEmpty()) return Result.success()

        val endpoint = graph.settings.cloudEndpoint
        var delivered = 0
        var failed = 0
        for (message in candidates) {
            if (isStopped) break
            val result = graph.uploader.upload(endpoint, message, CHANNEL)
            if (result.isSuccess) {
                graph.repository.markDelivered(message.id, "DIRECT_SYNC", "direct ${result.describe()}")
                delivered++
            } else {
                graph.repository.recordError(message.id, "Direct sync failed: ${result.describe()}")
                failed++
            }
        }

        if (delivered > 0 || failed > 0) {
            RelayStateHolder.log("Auto-sync: $delivered delivered directly, $failed failed")
        }
        return if (failed > 0 && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val CHANNEL = "direct-sync"
        private const val MAX_RETRIES = 5
        private const val PERIODIC_WORK = "zir-auto-sync-periodic"
        private const val ONE_SHOT_WORK = "zir-auto-sync-now"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Every 15 minutes (WorkManager's minimum) whenever a network is available. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Runs as soon as the system sees a network (immediately if online now). */
        fun syncSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
