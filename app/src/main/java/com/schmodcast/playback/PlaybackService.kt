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
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.schmodcast.R
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

// Whatever is currently at the head of the (date-sorted) queue plays first, but the
// player's own timeline always holds the *entire* queue (current episode at index 0,
// the rest following in date order) rather than just one item - this is what lets
// Android Auto's built-in Queue screen show real upcoming episodes instead of just the
// one playing now, the same way it would for a real ExoPlayer playlist. There's still
// no manual reordering, so "what's next" is entirely derived from the database: ExoPlayer
// auto-advancing to timeline index 1 is what finishing an episode actually looks like,
// and that transition is what triggers marking it played (see onMediaItemTransition).
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var episodeRepository: EpisodeRepository
    private lateinit var playbackStateStore: PlaybackStateStore
    private var mediaSession: MediaLibrarySession? = null
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
            // Only the very last item in the timeline ends this way - finishing any
            // earlier item instead fires onMediaItemTransition with reason AUTO, since
            // ExoPlayer moves straight on to whatever's already queued at the next index.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val finishedId = currentEpisodeId ?: return
                    serviceScope.launch { episodeRepository.markPlayed(finishedId) }
                }
            }

            // Fires when ExoPlayer itself advances to the next timeline item - i.e. an
            // episode finished with more already queued after it. Excludes other transition
            // reasons (a full setMediaItems() replace, a seek, etc.) via the `finishedId !=
            // newId` check, since currentEpisodeId is already updated to the new episode's
            // id by the time those calls trigger this callback.
            //
            // The timeline's next item is only as fresh as the last refreshTail() call, so
            // it's treated as a suggestion, not authority: the actual head of latestQueue
            // (the repo's live, date-sorted queue - our one source of truth for "what's
            // next", same as before this timeline held more than one item) is what decides.
            // If the two agree, nothing more to do. If the queue changed underneath the
            // timeline (a fresher episode arrived, an out-of-order pick's "true next" isn't
            // adjacent to it in the timeline, etc.), loadEpisode() forcibly reloads onto the
            // correct episode instead of leaving ExoPlayer's already-started guess in place.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                val finishedId = currentEpisodeId ?: return
                val newId = mediaItem?.mediaId ?: return
                if (finishedId == newId) return
                serviceScope.launch { episodeRepository.markPlayed(finishedId) }
                val trueNext = latestQueue.firstOrNull { it.id != finishedId }
                if (trueNext != null && trueNext.id != newId) {
                    loadEpisode(trueNext, autoPlay = true)
                } else {
                    currentEpisodeId = newId
                    playbackStateStore.currentEpisodeId = newId
                    // The tail items built by orderedMediaItems()/refreshTail() carry no
                    // position info (unlike loadEpisode(), which passes it via setMediaItems'
                    // startPositionMs) - ExoPlayer just auto-advanced onto this one at position
                    // 0, so if it was previously partway through, seek to resume it here too.
                    latestQueue.find { it.id == newId }?.let { episode ->
                        if (episode.lastPositionMs > 0L) player.seekTo(episode.lastPositionMs)
                    }
                    // ExoPlayer's own playhead just advanced past the finished episode, so
                    // the new current item now sits at whatever index it auto-advanced to
                    // (1, then 2, then 3...), not index 0. Trim the now-consumed items ahead
                    // of it so index 0 is "current" again - the invariant refreshTail() (and
                    // everything else that builds a timeline starting at index 0) relies on.
                    // Skipping this left a finished episode sitting where refreshTail() still
                    // expected the *next* one, so its periodic timeline refresh (triggered by
                    // the position-save ticker) clobbered the actually-playing item instead.
                    val newIndex = player.currentMediaItemIndex
                    if (newIndex > 0) {
                        player.removeMediaItems(0, newIndex)
                    }
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

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback).build()

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
                    // Current episode is unchanged - just refresh the upcoming portion of the
                    // timeline (new episodes fetched, one further down got marked played
                    // elsewhere, etc.) without touching index 0, so playback isn't interrupted.
                    stillPlayingCurrent -> refreshTail()
                    currentEpisodeId != null -> {
                        // The episode we were on disappeared from the repo without us seeing an
                        // AUTO transition first - e.g. the phone UI's manual "mark as played"
                        // bypasses the player entirely. Advance to the new head ourselves.
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
        player.setMediaItems(orderedMediaItems(episode), 0, resumePositionMs(episode))
        player.prepare()
        if (autoPlay) player.play()
    }

    // Builds the full player timeline for a given "current" episode: itself at index 0,
    // then the rest of the queue in its existing date order (out-of-order picks - a tap
    // from the phone UI or Auto's browse tree - naturally still resume the true queue head
    // next, rather than whatever happened to be adjacent to the tapped episode).
    private fun orderedMediaItems(current: Episode): List<MediaItem> {
        val tail = latestQueue.filterNot { it.id == current.id }.map { playableMediaItem(it) }
        return listOf(playableMediaItem(current)) + tail
    }

    private fun resumePositionMs(episode: Episode): Long =
        if (episode.lastPositionMs > 0L) episode.lastPositionMs else C.TIME_UNSET

    // Refreshes everything after the currently-playing item (index 0) to match the latest
    // queue contents, without touching index 0 itself - used when the queue Flow emits a
    // new list but what's actually playing hasn't changed, so this never interrupts playback.
    private fun refreshTail() {
        val currentId = currentEpisodeId ?: return
        val tailItems = latestQueue.filterNot { it.id == currentId }.map { playableMediaItem(it) }
        player.replaceMediaItems(1, player.mediaItemCount, tailItems)
    }

    // Shared by loadEpisode()/orderedMediaItems() (service-initiated playback) and the
    // session callback's onSetMediaItems (Android Auto/browser-initiated playback) so both
    // paths resolve the same real Uri - local file if downloaded, otherwise the streaming
    // audioUrl.
    private fun playableMediaItem(episode: Episode): MediaItem {
        val localFile = episode.localFilePath?.let { File(it) }?.takeIf { it.exists() }
        val uri = if (localFile != null) Uri.fromFile(localFile) else episode.audioUrl.toUri()
        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(uri)
            .setMediaMetadata(episodeMetadata(episode, isPlayable = true))
            .build()
    }

    // Browse-tree listing item for Android Auto - metadata only, no resolved Uri, since
    // the real Uri (local file vs. stream) is only resolved when something actually tries
    // to play it, via onSetMediaItems -> playableMediaItem().
    private fun browsableMediaItem(episode: Episode): MediaItem =
        MediaItem.Builder()
            .setMediaId(episode.id)
            .setMediaMetadata(episodeMetadata(episode, isPlayable = true))
            .build()

    private fun episodeMetadata(episode: Episode, isPlayable: Boolean): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .setIsPlayable(isPlayable)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .apply {
                if (episode.podcastArtworkUrl.isNotBlank()) {
                    setArtworkUri(episode.podcastArtworkUrl.toUri())
                }
            }
            .build()

    // Lets the queue UI hand-pick an episode to play out of order (tapping an "Up Next" row)
    // without going through the normal head-of-queue flow. loadEpisode() builds the tapped
    // episode's own ordered timeline (itself at index 0, the true queue head next), so once
    // it naturally finishes, ExoPlayer's own auto-advance (see onMediaItemTransition) resumes
    // playing whatever is at the head - no special-casing needed.
    private val sessionCallback = object : MediaLibrarySession.Callback {
        // Custom layout buttons show up as Android Auto's now-playing custom action slots (the
        // phone UI's own transport row is Compose, not this - see QueueScreen's TransportButton/
        // SpeedCycleButton). Rebuilt (not just built once at connect) because the speed button's
        // label needs to reflect whatever speed is current, same as the phone UI's own
        // SpeedCycleButton text.
        private fun customLayout(): List<CommandButton> = listOf(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_SKIP_BACK, Bundle.EMPTY))
                .setCustomIconResId(R.drawable.ic_skip_back)
                .setDisplayName("Skip back 30 seconds")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .setCustomIconResId(R.drawable.ic_skip_forward)
                .setDisplayName("Skip forward 2 minutes")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_CYCLE_SPEED, Bundle.EMPTY))
                .setCustomIconResId(R.drawable.ic_speed)
                .setDisplayName("${formatSpeedLabel(player.playbackParameters.speed)} speed")
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
                .build(),
        )

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_PLAY_EPISODE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SKIP_BACK, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_CYCLE_SPEED, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(connectionResult.availablePlayerCommands)
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_COMMAND_PLAY_EPISODE -> {
                    val episodeId = args.getString(EXTRA_EPISODE_ID)
                    val episode = latestQueue.find { it.id == episodeId }
                    if (episode != null) {
                        loadEpisode(episode, autoPlay = false)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_SKIP_BACK -> {
                    player.seekTo((player.currentPosition - SKIP_BACK_MS).coerceAtLeast(0L))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_SKIP_FORWARD -> {
                    val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + SKIP_FORWARD_MS).coerceAtMost(duration))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_CYCLE_SPEED -> {
                    player.setPlaybackSpeed(nextSpeed(player.playbackParameters.speed))
                    // Only this button's label goes stale over time (skip/forward icons never
                    // change), so only this command needs to push a refreshed layout.
                    mediaSession?.setCustomLayout(customLayout())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        // Android Auto (and any other MediaBrowser client) discovers content through this
        // browse tree rather than the phone app's "Up Next" list. Auto treats a root's direct
        // children as browsable tabs, not playable leaves - a root whose children are playable
        // items directly gets collapsed/skipped straight to Now Playing instead of showing a
        // browse screen. So the invisible root has exactly one browsable child, "Queue", and the
        // episodes (the phone UI's single flat, auto-sorted list, no manual folders/reordering)
        // live one level deeper under that.
        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        // The queue is bounded by EpisodeRepository's 60-day window, not a large catalog, so
        // page/pageSize are ignored rather than implementing real pagination.
        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return when (parentId) {
                ROOT_ID -> {
                    val queueFolder = MediaItem.Builder()
                        .setMediaId(QUEUE_FOLDER_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Queue")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                                .build(),
                        )
                        .build()
                    Futures.immediateFuture(LibraryResult.ofItemList(listOf(queueFolder), params))
                }
                QUEUE_FOLDER_ID -> {
                    val children = latestQueue.map { browsableMediaItem(it) }
                    Futures.immediateFuture(LibraryResult.ofItemList(children, params))
                }
                else -> Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val episode = latestQueue.find { it.id == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(browsableMediaItem(episode), null))
        }

        // Auto calls this when the user taps a browsed episode. The incoming MediaItem only
        // carries the mediaId (from browsableMediaItem(), no Uri) - resolve it back to a real
        // episode and hand back the full ordered timeline (same as loadEpisode() would build),
        // updating the same bookkeeping, so mark-played/auto-advance/position-save all keep
        // working - and Auto's own Queue screen shows the rest of the queue - regardless of
        // whether playback started from the phone UI or from Auto's browse tree.
        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requestedId = mediaItems.firstOrNull()?.mediaId
            val episode = latestQueue.find { it.id == requestedId }
                ?: return super.onSetMediaItems(session, controller, mediaItems, startIndex, startPositionMs)
            currentEpisodeId = episode.id
            playbackStateStore.currentEpisodeId = episode.id
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(orderedMediaItems(episode), 0, resumePositionMs(episode)),
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

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
        const val CUSTOM_COMMAND_SKIP_BACK = "com.schmodcast.SKIP_BACK"
        const val CUSTOM_COMMAND_SKIP_FORWARD = "com.schmodcast.SKIP_FORWARD"
        const val CUSTOM_COMMAND_CYCLE_SPEED = "com.schmodcast.CYCLE_SPEED"
        const val EXTRA_EPISODE_ID = "episodeId"
        private const val ROOT_ID = "root"
        private const val QUEUE_FOLDER_ID = "queue_root"
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
