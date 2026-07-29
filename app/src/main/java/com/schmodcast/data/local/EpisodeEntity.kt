package com.schmodcast.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.schmodcast.data.model.Episode
import java.time.Instant

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: Long,
    val podcastTitle: String,
    val podcastArtworkUrl: String,
    val title: String,
    val audioUrl: String,
    val publishedAtEpochMillis: Long,
    val durationSeconds: Long?,
    val played: Boolean = false,
    val localFilePath: String? = null,
)

fun EpisodeEntity.toDomain() = Episode(
    id = id,
    podcastId = podcastId,
    podcastTitle = podcastTitle,
    podcastArtworkUrl = podcastArtworkUrl,
    title = title,
    audioUrl = audioUrl,
    publishedAt = Instant.ofEpochMilli(publishedAtEpochMillis),
    durationSeconds = durationSeconds,
    localFilePath = localFilePath,
)
