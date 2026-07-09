package com.privacyguard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IntrusionDao {

    @Insert
    suspend fun insert(entity: IntrusionEntity): Long

    @Query("SELECT * FROM intrusions ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun queryRecent(limit: Int): List<IntrusionEntity>

    @Query("SELECT * FROM intrusions WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    suspend fun queryRecentSince(sinceMs: Long): List<IntrusionEntity>

    @Query("UPDATE intrusions SET dismissed = 1 WHERE id = :id")
    suspend fun updateDismissed(id: Long)

    @Query("DELETE FROM intrusions WHERE timestampMs < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long): Int

    @Query("DELETE FROM intrusions")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM intrusions ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<IntrusionEntity>>

    @Query("SELECT COUNT(*) FROM intrusions")
    suspend fun getCount(): Int
}
