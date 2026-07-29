package com.schmodcast.data.model

import java.time.Instant

data class Episode(
    val id: String,
    val podcastId: Long,
    val podcastTitle: String,
    val podcastArtworkUrl: String,
    val title: String,
    val audioUrl: String,
    val publishedAt: Instant,
    val durationSeconds: Long?,
    val localFilePath: String? = null,
    val lastPositionMs: Long = 0L,
)
