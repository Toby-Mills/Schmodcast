package com.schmodcast.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.schmodcast.R
import com.schmodcast.data.model.Podcast
import com.schmodcast.episodeRepository
import com.schmodcast.subscriptionsRepository
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val repository = remember(context) { context.subscriptionsRepository() }
    val episodeRepository = remember(context) { context.episodeRepository() }
    val scope = rememberCoroutineScope()
    val subscriptions by repository.subscriptions.collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingUnsubscribe by remember { mutableStateOf<Podcast?>(null) }

    if (subscriptions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No subscriptions yet.\nFind a show in Search to get started.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(subscriptions, key = { it.id }) { podcast ->
                SwipeToUnsubscribeRow(
                    podcast = podcast,
                    onUnsubscribeRequest = { pendingUnsubscribe = podcast },
                )
            }
        }
    }

    val podcastToUnsubscribe = pendingUnsubscribe
    if (podcastToUnsubscribe != null) {
        AlertDialog(
            onDismissRequest = { pendingUnsubscribe = null },
            title = { Text("Unsubscribe?") },
            text = { Text("You'll stop receiving new episodes of \"${podcastToUnsubscribe.title}\".") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnsubscribe = null
                    scope.launch {
                        repository.toggleSubscription(podcastToUnsubscribe)
                        episodeRepository.removeForPodcast(podcastToUnsubscribe.id)
                    }
                }) {
                    Text("Unsubscribe")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnsubscribe = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToUnsubscribeRow(podcast: Podcast, onUnsubscribeRequest: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
                onUnsubscribeRequest()
            }
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val alignment = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "Unsubscribe",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        SubscriptionRow(podcast)
    }
}

@Composable
private fun SubscriptionRow(podcast: Podcast) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = podcast.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column {
            Text(text = podcast.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(text = podcast.author, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}
