package com.schmodcast.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.schmodcast.R
import com.schmodcast.data.model.Episode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")

@Composable
fun QueueScreen(viewModel: QueueViewModel = viewModel()) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    if (queue.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Your queue is empty.\nSubscribe to a show in Search to get started.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NowPlayingCard(
            episode = queue.first(),
            isPlaying = playbackState.isPlaying,
            positionMs = playbackState.positionMs,
            durationMs = playbackState.durationMs,
            onPlayPauseClick = viewModel::onPlayPauseClick,
            onSeek = viewModel::onSeek,
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
                items(upNext, key = { it.id }) { episode -> UpNextRow(episode) }
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
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Card(modifier = Modifier.padding(16.dp)) {
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

            if (durationMs > 0) {
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
                    valueRange = 0f..durationMs.toFloat(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = formatDuration(displayedPositionMs.toLong()), style = MaterialTheme.typography.bodySmall)
                    Text(text = formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun UpNextRow(episode: Episode) {
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
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
