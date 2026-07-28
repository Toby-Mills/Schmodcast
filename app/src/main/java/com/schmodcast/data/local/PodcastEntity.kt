package com.schmodcast.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.schmodcast.data.model.Podcast

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val author: String,
    val artworkUrl: String,
    val feedUrl: String,
)

fun PodcastEntity.toDomain() = Podcast(
    id = id,
    title = title,
    author = author,
    artworkUrl = artworkUrl,
    feedUrl = feedUrl,
)

fun Podcast.toEntity() = PodcastEntity(
    id = id,
    title = title,
    author = author,
    artworkUrl = artworkUrl,
    feedUrl = feedUrl,
)
