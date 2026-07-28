package com.schmodcast.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM podcasts WHERE id = :id)")
    suspend fun isSubscribed(id: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(podcast: PodcastEntity)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
