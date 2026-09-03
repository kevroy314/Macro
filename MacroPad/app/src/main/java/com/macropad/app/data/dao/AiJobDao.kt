package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.AiJob
import kotlinx.coroutines.flow.Flow

@Dao
interface AiJobDao {
    @Query("SELECT * FROM ai_jobs ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<AiJob>>

    @Query("SELECT * FROM ai_jobs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<AiJob>

    @Query("SELECT * FROM ai_jobs WHERE clientJobId = :clientJobId")
    suspend fun getByClientId(clientJobId: String): AiJob?

    @Query("SELECT * FROM ai_jobs WHERE serverJobId = :serverJobId")
    suspend fun getByServerId(serverJobId: String): AiJob?

    @Query("SELECT * FROM ai_jobs WHERE status IN (:statuses)")
    suspend fun getByStatuses(statuses: Collection<String>): List<AiJob>

    @Query("SELECT * FROM ai_jobs WHERE status IN (:statuses)")
    fun getByStatusesFlow(statuses: Collection<String>): Flow<List<AiJob>>

    @Query("SELECT COALESCE(MAX(serverUpdatedAt), 0) FROM ai_jobs")
    suspend fun getLatestServerUpdate(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: AiJob)

    @Query("DELETE FROM ai_jobs WHERE clientJobId = :clientJobId")
    suspend fun deleteByClientId(clientJobId: String)
}
