package com.schmodcast.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.schmodcast.ui.search.SearchDialog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val repository = remember(context) { context.subscriptionsRepository() }
    val episodeRepository = remember(context) { context.episodeRepository() }
    val scope = rememberCoroutineScope()
    val subscriptions by repository.subscriptions.collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingUnsubscribe by remember { mutableStateOf<Podcast?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }

    Scaffold(
        // The outer Scaffold in MainActivity already reserves space for the status bar
        // (via its topBar) and the nav bar (via its bottomBar), and hands this screen
        // padding that already accounts for both. Leaving this inner Scaffold's default
        // contentWindowInsets (WindowInsets.safeDrawing) in place double-applies the status
        // bar inset as extra top padding, which is what showed up as blank space above the list.
        contentWindowInsets = WindowInsets(0.dp),
        floatingActionButton = {
            FloatingActionButton(onClick = { showSearchDialog = true }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Add podcast")
            }
        },
    ) { innerPadding ->
        if (subscriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No subscriptions yet.\nTap + to add a show.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            val listState = rememberLazyListState()
            val canScrollUp by remember { derivedStateOf { listState.canScrollBackward } }
            val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    state = listState,
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
                if (canScrollUp) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.background,
                                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    ),
                                ),
                            ),
                    )
                }
                if (canScrollDown) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.background,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
    }

    if (showSearchDialog) {
        SearchDialog(onDismissRequest = { showSearchDialog = false })
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
                        // NonCancellable: this screen's composable - and this rememberCoroutineScope
                        // along with it - is disposed the moment the user switches bottom-nav tabs,
                        // which naturally happens right after confirming (e.g. flipping to Queue to
                        // check the episodes are gone). Without this, a tab switch mid-flight could
                        // cancel the work between the two calls, unsubscribing the podcast but
                        // leaving its episodes stuck in the queue forever.
                        withContext(NonCancellable) {
                            repository.toggleSubscription(podcastToUnsubscribe)
                            episodeRepository.removeForPodcast(podcastToUnsubscribe.id)
                        }
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
