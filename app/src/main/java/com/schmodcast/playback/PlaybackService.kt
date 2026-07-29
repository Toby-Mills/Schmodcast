package com.schmodcast.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.schmodcast.data.EpisodeRepository
import com.schmodcast.data.PlaybackStateStore
import com.schmodcast.data.model.Episode
import com.schmodcast.episodeRepository
import com.schmodcast.playbackStateStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// Plays exactly one episode at a time - whatever is currently at the head of the
// (date-sorted) queue. There's no manual reordering, so "what's next" is entirely
// derived from the database: finishing an episode marks it played, which drops it
// out of the queue Flow and lets the new head take over.
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var episodeRepository: EpisodeRepository
    private lateinit var playbackStateStore: PlaybackStateStore
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentEpisodeId: String? = null
    private var latestQueue: List<Episode> = emptyList()
    private var positionSaveJob: Job? = null
    private var hasLoadedInitialEpisode = false

    override fun onCreate() {
        super.onCreate()
        episodeRepository = episodeRepository()
        playbackStateStore = playbackStateStore()

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

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPositionSaveTicker()
                } else {
                    stopPositionSaveTicker()
                    savePosition()
                }
            }

            // Catches seeks regardless of source (skip buttons, slider drags, or the
            // out-of-order "play this episode" custom command all end up here), so the
            // saved resume point stays current even if the app never pauses first.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                savePosition()
            }
        })

        mediaSession = MediaSession.Builder(this, player).setCallback(sessionCallback).build()

        serviceScope.launch {
            episodeRepository.queue.collect { episodes ->
                latestQueue = episodes
                if (!hasLoadedInitialEpisode) {
                    hasLoadedInitialEpisode = true
                    // Resume whatever episode was last in progress (even if it isn't the queue
                    // head) rather than always restarting at the head on a fresh launch.
                    val resumeId = playbackStateStore.currentEpisodeId
                    val toLoad = episodes.firstOrNull { it.id == resumeId } ?: episodes.firstOrNull()
                    toLoad?.let { loadEpisode(it, autoPlay = false) }
                    return@collect
                }
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

    private fun startPositionSaveTicker() {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                savePosition()
            }
        }
    }

    private fun stopPositionSaveTicker() {
        positionSaveJob?.cancel()
    }

    private fun savePosition() {
        val episodeId = currentEpisodeId ?: return
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        serviceScope.launch { episodeRepository.updatePosition(episodeId, positionMs) }
    }

    private fun loadEpisode(episode: Episode, autoPlay: Boolean) {
        currentEpisodeId = episode.id
        playbackStateStore.currentEpisodeId = episode.id
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
        if (episode.lastPositionMs > 0L) {
            player.seekTo(episode.lastPositionMs)
        }
        player.prepare()
        if (autoPlay) player.play()
    }

    // Lets the queue UI hand-pick an episode to play out of order (tapping an "Up Next" row)
    // without going through the normal head-of-queue flow. loadEpisode() sets currentEpisodeId,
    // so once this episode naturally finishes, the queue collector's existing auto-advance
    // logic takes back over and resumes playing whatever is at the head - no special-casing needed.
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_PLAY_EPISODE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, connectionResult.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CUSTOM_COMMAND_PLAY_EPISODE) {
                val episodeId = args.getString(EXTRA_EPISODE_ID)
                val episode = latestQueue.find { it.id == episodeId }
                if (episode != null) {
                    loadEpisode(episode, autoPlay = false)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        stopPositionSaveTicker()
        // Capture before releasing the player/cancelling the scope - serviceScope.cancel() would
        // otherwise cancel a just-launched save coroutine before it gets to run.
        val finalEpisodeId = currentEpisodeId
        val finalPositionMs = player.currentPosition.coerceAtLeast(0L)
        mediaSession?.let {
            it.player.release()
            it.release()
        }
        mediaSession = null
        serviceScope.cancel()
        if (finalEpisodeId != null) {
            runBlocking { episodeRepository.updatePosition(finalEpisodeId, finalPositionMs) }
        }
        super.onDestroy()
    }

    companion object {
        const val CUSTOM_COMMAND_PLAY_EPISODE = "com.schmodcast.PLAY_EPISODE"
        const val EXTRA_EPISODE_ID = "episodeId"
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
