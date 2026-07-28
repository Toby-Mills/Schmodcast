package com.schmodcast

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.schmodcast.data.SubscriptionsRepository
import com.schmodcast.data.local.SchmodcastDatabase

class SchmodcastApplication : Application() {
    private val database: SchmodcastDatabase by lazy {
        Room.databaseBuilder(this, SchmodcastDatabase::class.java, "schmodcast.db").build()
    }

    val subscriptionsRepository: SubscriptionsRepository by lazy {
        SubscriptionsRepository(database.podcastDao())
    }
}

fun Context.subscriptionsRepository(): SubscriptionsRepository =
    (applicationContext as SchmodcastApplication).subscriptionsRepository
