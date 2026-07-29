package com.schmodcast.ui.queue

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.schmodcast.data.download.DownloadState
import com.schmodcast.data.model.Episode
import com.schmodcast.episodeDownloadManager
import com.schmodcast.episodeRepository
import com.schmodcast.playback.PlaybackService
import com.schmodcast.subscriptionsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val currentEpisodeId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
)

private const val SKIP_FORWARD_MS = 2 * 60 * 1000L
private const val SKIP_BACK_MS = 30 * 1000L

class QueueViewModel(application: Application) : AndroidViewModel(application) {
    private val episodeRepository = application.episodeRepository()
    private val subscriptionsRepository = application.subscriptionsRepository()
    private val downloadManager = application.episodeDownloadManager()

    val queue: StateFlow<List<Episode>> = episodeRepository.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadStates: StateFlow<Map<String, DownloadState>> =
        combine(queue, downloadManager.states) { episodes, transientStates ->
            episodes.associate { episode ->
                val state = transientStates[episode.id]
                    ?: if (episode.localFilePath != null) DownloadState.Downloaded else DownloadState.NotDownloaded
                episode.id to state
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private var controller: MediaController? = null
    private var positionTicker: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncStateFromController()
            if (isPlaying) startPositionTicker() else stopPositionTicker()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromController()
        }
    }

    init {
        viewModelScope.launch {
            val podcasts = subscriptionsRepository.subscriptions.first()
            Log.d("QueueViewModel", "Refreshing ${podcasts.size} subscription(s): ${podcasts.map { it.title }}")
            episodeRepository.refreshAll(podcasts)
            episodeRepository.pruneOldEpisodes()
            Log.d("QueueViewModel", "Refresh pass complete")
        }
        connectController()
    }

    private fun connectController() {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            {
                controller = future.get().also { it.addListener(playerListener) }
                syncStateFromController()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun syncStateFromController() {
        val c = controller ?: return
        _playbackState.update {
            it.copy(
                isPlaying = c.isPlaying,
                currentEpisodeId = c.currentMediaItem?.mediaId,
                positionMs = c.currentPosition,
                durationMs = c.duration.coerceAtLeast(0L),
                playbackSpeed = c.playbackParameters.speed,
            )
        }
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = viewModelScope.launch {
            while (isActive) {
                syncStateFromController()
                delay(500)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
    }

    fun onPlayPauseClick() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun onSeek(positionMs: Long) {
        controller?.seekTo(positionMs)
        // Reflect the seek immediately: syncStateFromController() only otherwise runs from the
        // play/pause listener or the position ticker, and the ticker only runs while playing — so
        // while paused, nothing would update positionMs and the slider would snap back to its
        // pre-seek value the moment the drag released.
        _playbackState.update { it.copy(positionMs = positionMs) }
    }

    fun onSkipForward() {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + SKIP_FORWARD_MS).coerceAtMost(duration))
    }

    fun onSkipBack() {
        val c = controller ?: return
        c.seekTo((c.currentPosition - SKIP_BACK_MS).coerceAtLeast(0L))
    }

    fun onSpeedChange(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _playbackState.update { it.copy(playbackSpeed = speed) }
    }

    fun onEpisodeClick(episode: Episode) {
        val c = controller ?: return
        val args = Bundle().apply { putString(PlaybackService.EXTRA_EPISODE_ID, episode.id) }
        c.sendCustomCommand(SessionCommand(PlaybackService.CUSTOM_COMMAND_PLAY_EPISODE, Bundle.EMPTY), args)
    }

    fun onMarkPlayedClick() {
        val episodeId = playbackState.value.currentEpisodeId ?: return
        viewModelScope.launch { episodeRepository.markPlayed(episodeId) }
    }

    fun onDownloadClick(episode: Episode) {
        when (downloadStates.value[episode.id]) {
            is DownloadState.Downloaded, is DownloadState.Downloading -> downloadManager.cancelOrRemove(episode)
            else -> downloadManager.download(episode)
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        positionTicker?.cancel()
    }
}
