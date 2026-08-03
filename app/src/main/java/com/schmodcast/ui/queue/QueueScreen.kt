package com.schmodcast.ui.queue

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.schmodcast.R
import com.schmodcast.data.download.DownloadState
import com.schmodcast.data.model.Episode
import com.schmodcast.playback.formatSpeedLabel
import com.schmodcast.playback.nextSpeed
import com.schmodcast.ui.theme.SchmodcastNavy
import com.schmodcast.ui.theme.SchmodcastTeal
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")

// A flick whose velocity alone would cross the full collapsed-to-expanded range in well under a
// second commits to that direction outright, instead of requiring the drag to actually cross the
// halfway mark first.
private const val FLING_VELOCITY_THRESHOLD = 1.5f

@Composable
fun QueueScreen(viewModel: QueueViewModel = viewModel()) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()

    if (queue.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Your queue is empty.\nSubscribe to a show in Search to get started.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    val dragProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        val nowPlaying = queue.firstOrNull { it.id == playbackState.currentEpisodeId } ?: queue.first()
        NowPlayingCard(
            episode = nowPlaying,
            isPlaying = playbackState.isPlaying,
            positionMs = playbackState.positionMs,
            durationMs = playbackState.durationMs,
            playbackSpeed = playbackState.playbackSpeed,
            downloadState = downloadStates[nowPlaying.id] ?: DownloadState.NotDownloaded,
            dragProgress = dragProgress.value,
            onDragProgressDelta = { delta ->
                coroutineScope.launch {
                    dragProgress.snapTo((dragProgress.value + delta).coerceIn(0f, 1f))
                }
            },
            // velocity is signed, in progress-units/second (see dragRangePx in NowPlayingCard) — a
            // fast flick commits to the direction it's moving even if the halfway mark wasn't
            // reached, rather than always falling back to whichever side progress is closer to.
            onDragEnd = { velocity ->
                val target = when {
                    velocity > FLING_VELOCITY_THRESHOLD -> 1f
                    velocity < -FLING_VELOCITY_THRESHOLD -> 0f
                    dragProgress.value > 0.5f -> 1f
                    else -> 0f
                }
                coroutineScope.launch {
                    dragProgress.animateTo(
                        targetValue = target,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                        initialVelocity = velocity,
                    )
                }
            },
            onPlayPauseClick = viewModel::onPlayPauseClick,
            onSeek = viewModel::onSeek,
            onSkipForward = viewModel::onSkipForward,
            onSkipBack = viewModel::onSkipBack,
            onMarkPlayed = viewModel::onMarkPlayedClick,
            onSpeedChange = viewModel::onSpeedChange,
            onDownloadClick = { viewModel.onDownloadClick(nowPlaying) },
        )

        if (queue.isNotEmpty()) {
            Text(
                text = "Up Next",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(queue, key = { it.id }) { episode ->
                    UpNextRow(
                        episode = episode,
                        downloadState = downloadStates[episode.id] ?: DownloadState.NotDownloaded,
                        onDownloadClick = { viewModel.onDownloadClick(episode) },
                        onClick = { viewModel.onEpisodeClick(episode) },
                        isSelected = episode.id == nowPlaying.id,
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    episode: Episode,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    downloadState: DownloadState,
    dragProgress: Float,
    onDragProgressDelta: (Float) -> Unit,
    onDragEnd: (velocity: Float) -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onMarkPlayed: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDownloadClick: () -> Unit,
) {
    val expanded = dragProgress > 0.5f

    BoxWithConstraints(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
    ) {
        val density = LocalDensity.current
        val expandedHeight = maxHeight * 0.8f
        val totalHeight = lerp(COLLAPSED_PLAYER_HEIGHT, expandedHeight, dragProgress)
        val cardHeight = totalHeight - HANDLE_ROW_HEIGHT
        // Converts a raw pixel drag delta from the handle into a progress delta, scaled
        // against how much vertical travel actually separates collapsed from expanded.
        val dragRangePx = with(density) { (expandedHeight - COLLAPSED_PLAYER_HEIGHT).toPx().coerceAtLeast(1f) }

        // The card is anchored to the top of the screen and grows downward, so dragging the
        // handle down (positive dragAmount) should expand it, matching the pre-existing
        // pull-to-expand behavior.
        val onHandleDrag: (Float) -> Unit = { dragAmount -> onDragProgressDelta(dragAmount / dragRangePx) }
        // Same conversion applied to the fling velocity reported when the drag is released, so
        // "1.0" consistently means "would cross the full collapsed-to-expanded range in a second"
        // regardless of screen size.
        val onHandleDragEnd: (Float) -> Unit = { velocityPx -> onDragEnd(velocityPx / dragRangePx) }

        Column(modifier = Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            ) {
                // The handle lives outside the Card (below), not nested inside either branch here,
                // for two reasons: (1) it stays mounted across the content swap that happens when
                // dragging past the halfway point, so an in-flight gesture keeps tracking instead
                // of being disposed and remounted mid-drag; (2) Card clips its content to its own
                // rounded-rect bounds, which was cutting off the bottom half of the handle's
                // enlarged touch target when the handle lived at the card's bottom edge.
                if (expanded) {
                    ExpandedPlayerBody(
                        episode = episode,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        playbackSpeed = playbackSpeed,
                        downloadState = downloadState,
                        onPlayPauseClick = onPlayPauseClick,
                        onSeek = onSeek,
                        onSkipForward = onSkipForward,
                        onSkipBack = onSkipBack,
                        onMarkPlayed = onMarkPlayed,
                        onSpeedChange = onSpeedChange,
                        onDownloadClick = onDownloadClick,
                    )
                } else {
                    CollapsedPlayerBody(
                        episode = episode,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        downloadState = downloadState,
                        onPlayPauseClick = onPlayPauseClick,
                        onSeek = onSeek,
                        onDownloadClick = onDownloadClick,
                    )
                }
            }
            DragHandle(
                onDrag = onHandleDrag,
                onDragEnd = onHandleDragEnd,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

// Approximate height of CollapsedPlayerBody's natural wrap-content layout (Card + handle row
// combined), used both as the anchor for the collapsed end of the drag range and as the fixed
// total height at rest — the height is always explicit now (see onHandleDrag comment above), so
// this can no longer be sidestepped by falling back to true wrap-content sizing at progress == 0.
// 200.dp was too tight for the artwork row plus the slider and its timestamp row beneath it,
// clipping the timestamps; 240.dp gives them room without touching expandedHeight (still maxHeight
// * 0.8f), so the drag range just narrows slightly instead of the card growing into new screen space.
private val COLLAPSED_PLAYER_HEIGHT = 240.dp

// The handle's reserved row: the 4dp pill plus its 8dp top/bottom padding. Subtracted from the
// interpolated total height to get the Card's own height, since the handle now sits below the
// Card rather than inside it.
private val HANDLE_ROW_HEIGHT = 20.dp

@Composable
private fun CollapsedPlayerBody(
    episode: Episode,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    downloadState: DownloadState,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onDownloadClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = episode.podcastArtworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = episode.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(text = episode.podcastTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            DownloadIndicator(state = downloadState, onClick = onDownloadClick)
            IconButton(onClick = onPlayPauseClick) {
                if (isPlaying) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = "Pause",
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }

        PlaybackProgressSlider(positionMs = positionMs, durationMs = durationMs, onSeek = onSeek)
    }
}

// Height this body's fixed-dp chrome (artwork, spacers, transport buttons) was designed against,
// at the platform's default 1x font scale. `expandedHeight` in NowPlayingCard is a fixed 80% of
// screen height regardless of font size, so a large system font scale (which only grows the Text
// composables here, not the artwork/buttons/spacers) can make the two title lines alone tall
// enough that the fixed-size chrome no longer fits — on a real device (Pixel, 420dpi, 1.15x font
// scale) this silently clipped the download/speed/mark-played row entirely, with only a sliver of
// the speed pill's rounded top edge peeking out under the Card's clip. `chromeScale` below shrinks
// just that non-text chrome to make room, in proportion to both how much shorter than this
// reference the available height is *and* how much the font scale has grown the text beyond 1x —
// text itself is left alone (shrinking it would fight the user's own accessibility setting), and
// the existing verticalScroll stays as a fallback for whatever this heuristic doesn't fully cover.
private val EXPANDED_CONTENT_REFERENCE_HEIGHT = 620.dp
private const val EXPANDED_CHROME_MIN_SCALE = 0.7f

@Composable
private fun ExpandedPlayerBody(
    episode: Episode,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    downloadState: DownloadState,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onMarkPlayed: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDownloadClick: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val heightRatio = (maxHeight / EXPANDED_CONTENT_REFERENCE_HEIGHT).coerceAtMost(1f)
        val fontScalePenalty = (1f / fontScale).coerceAtMost(1f)
        val chromeScale = (heightRatio * fontScalePenalty).coerceIn(EXPANDED_CHROME_MIN_SCALE, 1f)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp * chromeScale),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = episode.podcastArtworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(180.dp * chromeScale)
                    .clip(RoundedCornerShape(20.dp)),
            )

            Spacer(modifier = Modifier.height(20.dp * chromeScale))

            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = episode.podcastTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(16.dp * chromeScale))

            PlaybackProgressSlider(positionMs = positionMs, durationMs = durationMs, onSeek = onSeek)

            Spacer(modifier = Modifier.height(8.dp * chromeScale))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    painter = painterResource(R.drawable.ic_skip_back),
                    label = "30",
                    contentDescription = "Skip back 30 seconds",
                    diameter = 64.dp * chromeScale,
                    iconSize = 26.dp * chromeScale,
                    containerColor = SchmodcastTeal.copy(alpha = 0.16f),
                    contentColor = SchmodcastNavy,
                    onClick = onSkipBack,
                )
                PlayPauseButton(
                    isPlaying = isPlaying,
                    onClick = onPlayPauseClick,
                    diameter = 112.dp * chromeScale,
                    pauseIconSize = 48.dp * chromeScale,
                    playIconSize = 56.dp * chromeScale,
                )
                TransportButton(
                    painter = painterResource(R.drawable.ic_skip_forward),
                    label = "2m",
                    contentDescription = "Skip forward 2 minutes",
                    diameter = 64.dp * chromeScale,
                    iconSize = 26.dp * chromeScale,
                    containerColor = SchmodcastTeal.copy(alpha = 0.16f),
                    contentColor = SchmodcastNavy,
                    onClick = onSkipForward,
                )
            }

            Spacer(modifier = Modifier.height(12.dp * chromeScale))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadIndicator(state = downloadState, onClick = onDownloadClick, iconSize = 28.dp * chromeScale)
                SpeedCycleButton(speed = playbackSpeed, onClick = { onSpeedChange(nextSpeed(playbackSpeed)) })
                IconButton(onClick = onMarkPlayed) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Mark as played",
                        modifier = Modifier.size(28.dp * chromeScale),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    diameter: Dp = 112.dp,
    pauseIconSize: Dp = 48.dp,
    playIconSize: Dp = 56.dp,
) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(SchmodcastNavy)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isPlaying) {
            Icon(
                painter = painterResource(R.drawable.ic_pause),
                contentDescription = "Pause",
                tint = Color.White,
                modifier = Modifier.size(pauseIconSize),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(playIconSize),
            )
        }
    }
}

@Composable
private fun TransportButton(
    painter: Painter,
    label: String,
    contentDescription: String,
    diameter: Dp,
    iconSize: Dp,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = contentColor)
    }
}

@Composable
private fun SpeedCycleButton(speed: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, SchmodcastTeal, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = "${formatSpeedLabel(speed)} speed",
            style = MaterialTheme.typography.labelLarge,
            color = SchmodcastTeal,
        )
    }
}

private val PROGRESS_TRACK_HEIGHT = 28.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackProgressSlider(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }
    val displayedPositionMs = if (isDragging) dragPositionMs else positionMs.toFloat()
    // ExoPlayer reports a resumed/seeked position before it's buffered enough to report the real
    // duration (duration stays 0/unset briefly after loadEpisode's seek), so positionMs can
    // legitimately exceed durationMs for a moment. Clamping the slider's own value keeps it from
    // rendering as maxed-out (100%) in that window instead of the true, still-loading progress.
    val sliderValue = if (isDragging) displayedPositionMs else displayedPositionMs.coerceAtMost(durationMs.toFloat())

    Slider(
        value = sliderValue,
        onValueChange = {
            isDragging = true
            dragPositionMs = it
        },
        onValueChangeFinished = {
            onSeek(dragPositionMs.toLong())
            isDragging = false
        },
        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
        track = { state -> LogoTiledTrack(state = state) },
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = formatDuration(displayedPositionMs.toLong()), style = MaterialTheme.typography.bodySmall)
        Text(text = formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
    }
}

// Replaces the Slider's default solid-color track with the app logo tiled across the filled
// portion, repeating at a fixed on-screen size regardless of the source PNG's resolution — the
// bitmap is pre-scaled to that tile size before being handed to ImageShader, since ImageShader
// otherwise tiles at the bitmap's native pixel size.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogoTiledTrack(state: SliderState, modifier: Modifier = Modifier) {
    val logoBrush = rememberTiledLogoBrush(tileSize = PROGRESS_TRACK_HEIGHT)
    val filledFraction = state.coercedValueAsFraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_TRACK_HEIGHT)
            .clip(RoundedCornerShape(PROGRESS_TRACK_HEIGHT / 2))
            // MaterialTheme.colorScheme.surfaceVariant lands close enough to the NowPlayingCard's
            // own container color under dynamic (Material You) theming to read as invisible;
            // outlineVariant is what the drag handle pill already relies on for contrast here.
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(filledFraction)
                .fillMaxHeight()
                // The logo PNG's own background is a near-white square around the navy badge, so
                // it's keyed out to transparent (see rememberTiledLogoBrush) and painted over a
                // navy fill here — otherwise every tile repeat shows a visible white square behind
                // the badge instead of one continuous navy background.
                .background(SchmodcastNavy)
                .background(logoBrush),
        )
    }
}

@Composable
private fun rememberTiledLogoBrush(tileSize: Dp): Brush {
    val context = LocalContext.current
    val tileSizePx = with(LocalDensity.current) { tileSize.roundToPx() }.coerceAtLeast(1)
    return remember(tileSizePx) {
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(sourceSize = 1254, targetSize = tileSizePx * 3)
        }
        val source = BitmapFactory.decodeResource(context.resources, R.drawable.schmodcast_logo, decodeOptions)
        val keyed = keyOutNearWhite(source)
        val tile = Bitmap.createScaledBitmap(keyed, tileSizePx, tileSizePx, true)
        ShaderBrush(ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
}

private fun calculateInSampleSize(sourceSize: Int, targetSize: Int): Int {
    var sampleSize = 1
    while (sourceSize / (sampleSize * 2) >= targetSize) {
        sampleSize *= 2
    }
    return sampleSize
}

// Turns the logo's near-white background transparent (it ships as an opaque RGB PNG with no
// alpha channel) so the badge can be tiled directly over a solid navy fill without each repeat
// carrying its own visible white square.
private fun keyOutNearWhite(source: Bitmap): Bitmap {
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        if (r > 235 && g > 235 && b > 235) {
            pixels[i] = 0
        }
    }
    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return bitmap
}

@Composable
private fun DragHandle(
    onDrag: (Float) -> Unit,
    onDragEnd: (velocity: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // draggable (rather than a raw detectVerticalDragGestures pointerInput) reports the release
    // velocity via onDragStopped, which is what lets a quick flick complete the transition without
    // needing to physically drag all the way across the halfway mark.
    val draggableState = rememberDraggableState { delta -> currentOnDrag(delta) }

    Box(
        // Grows the touch/drag-detection area well past the visible pill without reporting a
        // bigger size upward, so the layout footprint (and spacing around it) is unchanged. This
        // outer box must stay unclipped — Compose's clip() also restricts hit-testing to its own
        // bounds, which would silently cancel the expanded area out again.
        modifier = modifier
            .size(width = 32.dp, height = 4.dp)
            .expandTouchTarget(horizontal = 24.dp, vertical = 20.dp)
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> currentOnDragEnd(velocity) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private fun Modifier.expandTouchTarget(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val extraWidthPx = horizontal.roundToPx() * 2
    val extraHeightPx = vertical.roundToPx() * 2
    val expandedConstraints = Constraints.fixed(
        width = constraints.maxWidth + extraWidthPx,
        height = constraints.maxHeight + extraHeightPx,
    )
    val placeable = measurable.measure(expandedConstraints)
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(-extraWidthPx / 2, -extraHeightPx / 2)
    }
}

@Composable
private fun UpNextRow(
    episode: Episode,
    downloadState: DownloadState,
    onDownloadClick: () -> Unit,
    onClick: () -> Unit,
    isSelected: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isSelected) Modifier.background(SchmodcastTeal.copy(alpha = 0.14f)) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = episode.podcastArtworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.Top)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.podcastTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else null,
                color = if (isSelected) SchmodcastTeal else Color.Unspecified,
                maxLines = 2,
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Now playing",
                tint = SchmodcastTeal,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = DATE_FORMAT.format(episode.publishedAt.atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.bodySmall,
        )
        DownloadIndicator(state = downloadState, onClick = onDownloadClick)
    }
}

@Composable
private fun DownloadIndicator(state: DownloadState, onClick: () -> Unit, iconSize: Dp = 24.dp) {
    when (state) {
        is DownloadState.Downloading -> {
            IconButton(onClick = onClick) {
                if (state.progress > 0f) {
                    CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(iconSize))
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(iconSize))
                }
            }
        }
        is DownloadState.Downloaded -> {
            IconButton(onClick = onClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_download_filled),
                    contentDescription = "Downloaded, tap to remove",
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        is DownloadState.NotDownloaded, is DownloadState.Failed -> {
            IconButton(onClick = onClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_download_outline),
                    contentDescription = if (state is DownloadState.Failed) "Download failed, tap to retry" else "Download for offline playback",
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
