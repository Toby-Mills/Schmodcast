package com.schmodcast.data

import com.schmodcast.data.model.Podcast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Process-scoped only - no persistence yet, so subscriptions are lost on app restart.
object SubscriptionsRepository {
    private val _subscriptions = MutableStateFlow<List<Podcast>>(emptyList())
    val subscriptions: StateFlow<List<Podcast>> = _subscriptions.asStateFlow()

    fun toggleSubscription(podcast: Podcast) {
        _subscriptions.update { current ->
            if (current.any { it.id == podcast.id }) {
                current.filterNot { it.id == podcast.id }
            } else {
                current + podcast
            }
        }
    }
}
