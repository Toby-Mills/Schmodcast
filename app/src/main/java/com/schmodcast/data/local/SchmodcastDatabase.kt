package com.schmodcast.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PodcastEntity::class], version = 1, exportSchema = false)
abstract class SchmodcastDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
}
