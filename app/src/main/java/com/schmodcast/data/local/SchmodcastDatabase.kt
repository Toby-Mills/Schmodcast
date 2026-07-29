package com.schmodcast.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PodcastEntity::class, EpisodeEntity::class], version = 3, exportSchema = false)
abstract class SchmodcastDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
}
