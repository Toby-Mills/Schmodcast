package com.schmodcast.data.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.schmodcast.data.download.DownloadState
import com.schmodcast.episodeDownloadManager
import com.schmodcast.episodeRepository
import kotlinx.coroutines.flow.first

private const val TAG = "EpisodeDownloadWorker"
private const val UNIQUE_WORK_NAME = "episode_predownload"
private const val PREDOWNLOAD_COUNT = 3

class EpisodeDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val downloadManager = applicationContext.episodeDownloadManager()
        val episodes = applicationContext.episodeRepository().nextUndownloaded(PREDOWNLOAD_COUNT)
        episodes.forEach { episode ->
            downloadManager.download(episode)
            downloadManager.states.first { states ->
                val state = states[episode.id]
                state is DownloadState.Downloaded || state is DownloadState.Failed
            }
        }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { e ->
            Log.w(TAG, "Background episode pre-download failed", e)
            Result.failure()
        },
    )

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
