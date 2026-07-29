package com.schmodcast.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.schmodcast.data.EpisodeRepository
import com.schmodcast.data.model.Episode
import com.schmodcast.episodeRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Plays exactly one episode at a time - whatever is currently at the head of the
// (date-sorted) queue. There's no manual reordering, so "what's next" is entirely
// derived from the database: finishing an episode marks it played, which drops it
// out of the queue Flow and lets the new head take over.
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var episodeRepository: EpisodeRepository
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentEpisodeId: String? = null

    override fun onCreate() {
        super.onCreate()
        episodeRepository = episodeRepository()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val finishedId = currentEpisodeId ?: return
                    serviceScope.launch { episodeRepository.markPlayed(finishedId) }
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()

        serviceScope.launch {
            episodeRepository.queue.collect { episodes ->
                val stillPlayingCurrent = currentEpisodeId != null && episodes.any { it.id == currentEpisodeId }
                when {
                    stillPlayingCurrent -> Unit // don't interrupt what's already loaded
                    currentEpisodeId != null -> {
                        // the episode we were on finished (and was marked played) - advance
                        currentEpisodeId = null
                        episodes.firstOrNull()?.let { loadEpisode(it, autoPlay = true) }
                    }
                    else -> episodes.firstOrNull()?.let { loadEpisode(it, autoPlay = false) }
                }
            }
        }
    }

    private fun loadEpisode(episode: Episode, autoPlay: Boolean) {
        currentEpisodeId = episode.id
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .apply {
                if (episode.podcastArtworkUrl.isNotBlank()) {
                    setArtworkUri(episode.podcastArtworkUrl.toUri())
                }
            }
            .build()

        val localFile = episode.localFilePath?.let { File(it) }?.takeIf { it.exists() }
        val uri = if (localFile != null) Uri.fromFile(localFile) else episode.audioUrl.toUri()

        val mediaItem = MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        if (autoPlay) player.play()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.let {
            it.player.release()
            it.release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
