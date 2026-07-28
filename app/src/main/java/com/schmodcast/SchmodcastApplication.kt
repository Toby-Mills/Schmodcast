package com.schmodcast

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.schmodcast.data.EpisodeRepository
import com.schmodcast.data.SubscriptionsRepository
import com.schmodcast.data.local.SchmodcastDatabase
import com.schmodcast.data.remote.NetworkModule

class SchmodcastApplication : Application() {
    private val database: SchmodcastDatabase by lazy {
        Room.databaseBuilder(this, SchmodcastDatabase::class.java, "schmodcast.db")
            // Pre-release app, no installs to preserve across schema changes yet.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val subscriptionsRepository: SubscriptionsRepository by lazy {
        SubscriptionsRepository(database.podcastDao())
    }

    val episodeRepository: EpisodeRepository by lazy {
        EpisodeRepository(database.episodeDao(), NetworkModule.okHttpClient)
    }
}

fun Context.subscriptionsRepository(): SubscriptionsRepository =
    (applicationContext as SchmodcastApplication).subscriptionsRepository

fun Context.episodeRepository(): EpisodeRepository =
    (applicationContext as SchmodcastApplication).episodeRepository
