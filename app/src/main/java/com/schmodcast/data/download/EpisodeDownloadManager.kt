package com.schmodcast.data.download

import android.content.Context
import android.util.Log
import com.schmodcast.data.local.EpisodeDao
import com.schmodcast.data.model.Episode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "EpisodeDownloadManager"

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Downloaded : DownloadState
    data object Failed : DownloadState
}

// Downloads run on an application-scoped coroutine, so they stop if the process dies -
// there's no WorkManager-backed resilience yet, matching the "select + indicator" scope.
class EpisodeDownloadManager(
    private val context: Context,
    private val episodeDao: EpisodeDao,
    private val httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val downloadsDir: File
        get() = File(context.filesDir, "episode_downloads").apply { mkdirs() }

    fun download(episode: Episode) {
        if (jobs[episode.id]?.isActive == true) return
        jobs[episode.id] = scope.launch {
            _states.update { it + (episode.id to DownloadState.Downloading(0f)) }
            val destFile = File(downloadsDir, "${UUID.nameUUIDFromBytes(episode.id.toByteArray())}.media")

            runCatching {
                val request = Request.Builder().url(episode.audioUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    check(response.isSuccessful && body != null) { "HTTP ${response.code}" }
                    val total = body.contentLength()
                    var bytesRead = 0L
                    destFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                bytesRead += read
                                if (total > 0) {
                                    _states.update {
                                        it + (episode.id to DownloadState.Downloading(bytesRead.toFloat() / total))
                                    }
                                }
                            }
                        }
                    }
                }
                episodeDao.setLocalFilePath(episode.id, destFile.absolutePath)
            }.onSuccess {
                _states.update { it + (episode.id to DownloadState.Downloaded) }
            }.onFailure { e ->
                destFile.delete()
                Log.w(TAG, "Download failed for '${episode.title}'", e)
                _states.update { it + (episode.id to DownloadState.Failed) }
            }
            jobs.remove(episode.id)
        }
    }

    fun cancelOrRemove(episode: Episode) {
        jobs[episode.id]?.cancel()
        jobs.remove(episode.id)
        scope.launch {
            episode.localFilePath?.let { File(it).delete() }
            episodeDao.setLocalFilePath(episode.id, null)
            _states.update { it - episode.id }
        }
    }
}
