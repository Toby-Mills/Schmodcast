package com.schmodcast.data

import android.util.Log
import com.schmodcast.data.local.EpisodeDao
import com.schmodcast.data.local.EpisodeEntity
import com.schmodcast.data.local.toDomain
import com.schmodcast.data.model.Episode
import com.schmodcast.data.model.Podcast
import com.schmodcast.data.remote.RssFeedParser
import com.schmodcast.data.remote.RssItem
import com.schmodcast.data.remote.parsePubDate
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "EpisodeRepository"
private val QUEUE_WINDOW: Duration = Duration.ofDays(60)

class EpisodeRepository(
    private val episodeDao: EpisodeDao,
    private val httpClient: OkHttpClient,
) {
    val queue: Flow<List<Episode>> = episodeDao.observeQueue().map { entities -> entities.map { it.toDomain() } }

    suspend fun refreshFeed(podcast: Podcast) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(podcast.feedUrl).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    Log.w(TAG, "Feed fetch failed for '${podcast.title}': HTTP ${response.code} ${podcast.feedUrl}")
                    return@use
                }

                val items = RssFeedParser.parse(body.byteStream())
                val cutoff = Instant.now().minus(QUEUE_WINDOW)
                val entities = items.mapNotNull { it.toEntityOrNull(podcast, cutoff) }
                Log.d(TAG, "'${podcast.title}': parsed ${items.size} item(s), ${entities.size} within window")
                if (entities.isNotEmpty()) episodeDao.insertAll(entities)
            }
        }.onFailure { e ->
            Log.w(TAG, "Feed refresh threw for '${podcast.title}' (${podcast.feedUrl})", e)
        }
    }

    suspend fun refreshAll(podcasts: List<Podcast>) {
        podcasts.forEach { refreshFeed(it) }
    }

    suspend fun pruneOldEpisodes() {
        episodeDao.deleteOlderThan(Instant.now().minus(QUEUE_WINDOW).toEpochMilli())
    }

    suspend fun markPlayed(episodeId: String) = episodeDao.markPlayed(episodeId)

    suspend fun removeForPodcast(podcastId: Long) = episodeDao.deleteForPodcast(podcastId)
}

private fun RssItem.toEntityOrNull(podcast: Podcast, cutoff: Instant): EpisodeEntity? {
    val publishedAt = parsePubDate(pubDate) ?: return null
    if (publishedAt.isBefore(cutoff)) return null
    return EpisodeEntity(
        id = guid,
        podcastId = podcast.id,
        podcastTitle = podcast.title,
        podcastArtworkUrl = podcast.artworkUrl,
        title = title,
        audioUrl = audioUrl,
        publishedAtEpochMillis = publishedAt.toEpochMilli(),
        durationSeconds = durationSeconds,
    )
}
