package com.schmodcast.ui.search

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.schmodcast.data.model.Podcast
import com.schmodcast.episodeRepository
import com.schmodcast.subscriptionsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SearchDialog(onDismissRequest: () -> Unit, viewModel: SearchViewModel = viewModel()) {
    val context = LocalContext.current
    val repository = remember(context) { context.subscriptionsRepository() }
    val episodeRepository = remember(context) { context.episodeRepository() }
    val scope = rememberCoroutineScope()

    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val subscriptions by repository.subscriptions.collectAsStateWithLifecycle(initialValue = emptyList())
    val subscribedIds = subscriptions.mapTo(HashSet()) { it.id }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Add Podcast",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text("Search podcasts") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .focusRequester(focusRequester),
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    when (val state = uiState) {
                        is SearchUiState.Idle -> Text(
                            text = "Find a show to subscribe to.",
                            modifier = Modifier.padding(top = 24.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        is SearchUiState.Loading -> CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 24.dp),
                        )

                        is SearchUiState.Error -> Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 24.dp),
                        )

                        is SearchUiState.Success -> {
                            if (state.results.isEmpty()) {
                                Text(
                                    text = "No podcasts found.",
                                    modifier = Modifier.padding(top = 24.dp),
                                )
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(state.results, key = { it.id }) { podcast ->
                                        PodcastResultRow(
                                            podcast = podcast,
                                            isSubscribed = podcast.id in subscribedIds,
                                            onSelect = {
                                                if (podcast.id !in subscribedIds) {
                                                    scope.launch {
                                                        // NonCancellable: onDismissRequest() below disposes this
                                                        // composable (and this rememberCoroutineScope with it)
                                                        // right after the dialog closes - without this, that could
                                                        // cancel the subscribe partway through, e.g. adding the
                                                        // podcast row but never fetching its feed (see
                                                        // LibraryScreen's identical fix for the reverse case).
                                                        withContext(NonCancellable) {
                                                            val nowSubscribed = repository.toggleSubscription(podcast)
                                                            if (nowSubscribed) {
                                                                episodeRepository.refreshFeed(podcast)
                                                            }
                                                        }
                                                    }
                                                }
                                                onDismissRequest()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastResultRow(
    podcast: Podcast,
    isSubscribed: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
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
        Column(modifier = Modifier.weight(1f)) {
            Text(text = podcast.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(text = podcast.author, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        if (isSubscribed) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Subscribed",
            )
        }
    }
}
