package com.schmodcast.ui.queue

import android.app.Application
import android.content.ComponentName
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.schmodcast.data.model.Episode
import com.schmodcast.episodeRepository
import com.schmodcast.playback.PlaybackService
import com.schmodcast.subscriptionsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class QueueViewModel(application: Application) : AndroidViewModel(application) {
    private val episodeRepository = application.episodeRepository()
    private val subscriptionsRepository = application.subscriptionsRepository()

    val queue: StateFlow<List<Episode>> = episodeRepository.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        positionTicker?.cancel()
    }
}
