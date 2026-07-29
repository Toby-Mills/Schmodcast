package com.schmodcast.ui.queue

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.schmodcast.R
import com.schmodcast.data.download.DownloadState
import com.schmodcast.data.model.Episode
import com.schmodcast.ui.theme.SchmodcastNavy
import com.schmodcast.ui.theme.SchmodcastTeal
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")
private val SPEED_OPTIONS = listOf(1f, 1.2f, 1.4f, 1.6f, 1.8f, 2f)

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

    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        val nowPlaying = queue.first()
        NowPlayingCard(
            episode = nowPlaying,
            isPlaying = playbackState.isPlaying,
            positionMs = playbackState.positionMs,
            durationMs = playbackState.durationMs,
            playbackSpeed = playbackState.playbackSpeed,
            downloadState = downloadStates[nowPlaying.id] ?: DownloadState.NotDownloaded,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onPlayPauseClick = viewModel::onPlayPauseClick,
            onSeek = viewModel::onSeek,
            onSkipForward = viewModel::onSkipForward,
            onSkipBack = viewModel::onSkipBack,
            onMarkPlayed = viewModel::onMarkPlayedClick,
            onSpeedChange = viewModel::onSpeedChange,
            onDownloadClick = { viewModel.onDownloadClick(nowPlaying) },
        )

        val upNext = queue.drop(1)
        if (upNext.isNotEmpty()) {
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
                items(upNext, key = { it.id }) { episode ->
                    UpNextRow(
                        episode = episode,
                        downloadState = downloadStates[episode.id] ?: DownloadState.NotDownloaded,
                        onDownloadClick = { viewModel.onDownloadClick(episode) },
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
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onMarkPlayed: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDownloadClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .then(if (expanded) Modifier.fillMaxHeight(0.8f) else Modifier)
            .animateContentSize(),
    ) {
        if (expanded) {
            ExpandedPlayerContent(
                episode = episode,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                downloadState = downloadState,
                onExpandedChange = onExpandedChange,
                onPlayPauseClick = onPlayPauseClick,
                onSeek = onSeek,
                onSkipForward = onSkipForward,
                onSkipBack = onSkipBack,
                onMarkPlayed = onMarkPlayed,
                onSpeedChange = onSpeedChange,
                onDownloadClick = onDownloadClick,
            )
        } else {
            CollapsedPlayerRow(
                episode = episode,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                downloadState = downloadState,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                onPlayPauseClick = onPlayPauseClick,
                onSeek = onSeek,
                onDownloadClick = onDownloadClick,
            )
        }
    }
}

@Composable
private fun CollapsedPlayerRow(
    episode: Episode,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    downloadState: DownloadState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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

        DragHandle(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun ExpandedPlayerContent(
    episode: Episode,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    downloadState: DownloadState,
    onExpandedChange: (Boolean) -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onMarkPlayed: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDownloadClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = episode.podcastArtworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )

            Spacer(modifier = Modifier.height(20.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

            PlaybackProgressSlider(positionMs = positionMs, durationMs = durationMs, onSeek = onSeek)

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    painter = painterResource(R.drawable.ic_skip_back),
                    label = "30",
                    contentDescription = "Skip back 30 seconds",
                    diameter = 64.dp,
                    iconSize = 26.dp,
                    containerColor = SchmodcastTeal.copy(alpha = 0.16f),
                    contentColor = SchmodcastNavy,
                    onClick = onSkipBack,
                )
                PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPauseClick)
                TransportButton(
                    painter = painterResource(R.drawable.ic_skip_forward),
                    label = "2m",
                    contentDescription = "Skip forward 2 minutes",
                    diameter = 64.dp,
                    iconSize = 26.dp,
                    containerColor = SchmodcastTeal.copy(alpha = 0.16f),
                    contentColor = SchmodcastNavy,
                    onClick = onSkipForward,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadIndicator(state = downloadState, onClick = onDownloadClick, iconSize = 28.dp)
                SpeedCycleButton(speed = playbackSpeed, onClick = { onSpeedChange(nextSpeed(playbackSpeed)) })
                IconButton(onClick = onMarkPlayed) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Mark as played",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }

        DragHandle(
            expanded = true,
            onExpandedChange = onExpandedChange,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(112.dp)
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
                modifier = Modifier.size(48.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(56.dp),
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
            text = "${formatSpeed(speed)} speed",
            style = MaterialTheme.typography.labelLarge,
            color = SchmodcastTeal,
        )
    }
}

@Composable
private fun PlaybackProgressSlider(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }
    val displayedPositionMs = if (isDragging) dragPositionMs else positionMs.toFloat()

    Slider(
        value = displayedPositionMs,
        onValueChange = {
            isDragging = true
            dragPositionMs = it
        },
        onValueChangeFinished = {
            onSeek(dragPositionMs.toLong())
            isDragging = false
        },
        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = formatDuration(displayedPositionMs.toLong()), style = MaterialTheme.typography.bodySmall)
        Text(text = formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DragHandle(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var handled by remember { mutableStateOf(false) }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)

    Box(
        modifier = modifier
            .size(width = 32.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
            .clickable { currentOnExpandedChange(!currentExpanded) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragAccumulator = 0f
                        handled = false
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulator += dragAmount
                        if (!handled && !currentExpanded && dragAccumulator > DRAG_EXPAND_THRESHOLD_PX) {
                            currentOnExpandedChange(true)
                            handled = true
                        } else if (!handled && currentExpanded && dragAccumulator < -DRAG_EXPAND_THRESHOLD_PX) {
                            currentOnExpandedChange(false)
                            handled = true
                        }
                    },
                )
            },
    )
}

private const val DRAG_EXPAND_THRESHOLD_PX = 40f

private fun formatSpeed(speed: Float): String =
    String.format(java.util.Locale.US, "%.1fx", speed).replace(".0x", "x")

private fun nextSpeed(current: Float): Float {
    val index = SPEED_OPTIONS.indexOf(current).coerceAtLeast(0)
    return SPEED_OPTIONS[(index + 1) % SPEED_OPTIONS.size]
}

@Composable
private fun UpNextRow(episode: Episode, downloadState: DownloadState, onDownloadClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = episode.podcastArtworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = episode.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(text = episode.podcastTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
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
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
