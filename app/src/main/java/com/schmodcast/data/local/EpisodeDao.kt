package com.schmodcast.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE played = 0 ORDER BY publishedAtEpochMillis DESC")
    fun observeQueue(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<EpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("UPDATE episodes SET played = 1 WHERE id = :episodeId")
    suspend fun markPlayed(episodeId: String)

    @Query("UPDATE episodes SET localFilePath = :path WHERE id = :episodeId")
    suspend fun setLocalFilePath(episodeId: String, path: String?)

    @Query("UPDATE episodes SET lastPositionMs = :positionMs WHERE id = :episodeId")
    suspend fun updatePosition(episodeId: String, positionMs: Long)

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun getForPodcast(podcastId: Long): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteForPodcast(podcastId: Long)

    @Query("SELECT * FROM episodes WHERE publishedAtEpochMillis < :cutoffEpochMillis")
    suspend fun getOlderThan(cutoffEpochMillis: Long): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE publishedAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long)
}
