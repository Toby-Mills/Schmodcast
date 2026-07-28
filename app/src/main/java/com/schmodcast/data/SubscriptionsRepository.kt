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

    suspend fun toggleSubscription(podcast: Podcast) {
        if (podcastDao.isSubscribed(podcast.id)) {
            podcastDao.deleteById(podcast.id)
        } else {
            podcastDao.insert(podcast.toEntity())
        }
    }
}
