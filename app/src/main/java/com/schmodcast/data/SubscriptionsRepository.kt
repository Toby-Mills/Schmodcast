package com.schmodcast.data

import com.schmodcast.data.local.PodcastDao
import com.schmodcast.data.local.toDomain
import com.schmodcast.data.local.toEntity
import com.schmodcast.data.model.Podcast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SubscriptionsRepository(private val podcastDao: PodcastDao) {
    val subscriptions: Flow<List<Podcast>> =
        podcastDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Returns true if the podcast is now subscribed, false if it was just unsubscribed. */
    suspend fun toggleSubscription(podcast: Podcast): Boolean {
        val wasSubscribed = podcastDao.isSubscribed(podcast.id)
        if (wasSubscribed) {
            podcastDao.deleteById(podcast.id)
        } else {
            podcastDao.insert(podcast.toEntity())
        }
        return !wasSubscribed
    }
}
