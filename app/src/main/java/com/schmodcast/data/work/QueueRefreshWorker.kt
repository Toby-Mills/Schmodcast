package com.schmodcast.data.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.schmodcast.episodeRepository
import com.schmodcast.subscriptionsRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

private const val TAG = "QueueRefreshWorker"
private const val UNIQUE_WORK_NAME = "queue_refresh"
private val REFRESH_INTERVAL = 1L to TimeUnit.HOURS

class QueueRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val podcasts = applicationContext.subscriptionsRepository().subscriptions.first()
        applicationContext.episodeRepository().refreshAll(podcasts)
        applicationContext.episodeRepository().pruneOldEpisodes()
        EpisodeDownloadWorker.enqueue(applicationContext)
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { e ->
            Log.w(TAG, "Background queue refresh failed", e)
            Result.retry()
        },
    )

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QueueRefreshWorker>(REFRESH_INTERVAL.first, REFRESH_INTERVAL.second)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
