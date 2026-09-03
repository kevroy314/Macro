package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.AiThread
import com.macropad.app.data.entity.AiThreadMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface AiThreadDao {
    @Query("SELECT * FROM ai_threads ORDER BY updatedAt DESC")
    fun getAllFlow(): Flow<List<AiThread>>

    @Query("SELECT * FROM ai_threads WHERE clientThreadId = :clientThreadId")
    fun getByClientIdFlow(clientThreadId: String): Flow<AiThread?>

    @Query("SELECT * FROM ai_threads WHERE clientThreadId = :clientThreadId")
    suspend fun getByClientId(clientThreadId: String): AiThread?

    @Query("SELECT * FROM ai_threads WHERE serverThreadId = :serverThreadId")
    suspend fun getByServerId(serverThreadId: String): AiThread?

    @Query("SELECT * FROM ai_threads WHERE status IN ('running','sending')")
    suspend fun getBusy(): List<AiThread>

    @Query("SELECT COALESCE(MAX(serverUpdatedAt), 0) FROM ai_threads")
    suspend fun getLatestServerUpdate(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: AiThread)

    @Query("DELETE FROM ai_threads WHERE clientThreadId = :clientThreadId")
    suspend fun deleteByClientId(clientThreadId: String)
}

@Dao
interface AiThreadMessageDao {
    @Query("SELECT * FROM ai_thread_messages WHERE clientThreadId = :clientThreadId ORDER BY createdAt ASC")
    fun getForThreadFlow(clientThreadId: String): Flow<List<AiThreadMessage>>

    @Query("SELECT * FROM ai_thread_messages WHERE clientThreadId = :clientThreadId ORDER BY createdAt ASC")
    suspend fun getForThread(clientThreadId: String): List<AiThreadMessage>

    @Query("SELECT * FROM ai_thread_messages WHERE id = :id")
    suspend fun getById(id: String): AiThreadMessage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<AiThreadMessage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: AiThreadMessage)

    @Query("UPDATE ai_thread_messages SET appliedIndicesJson = :applied WHERE id = :id")
    suspend fun setAppliedIndices(id: String, applied: String)

    @Query("DELETE FROM ai_thread_messages WHERE clientThreadId = :clientThreadId")
    suspend fun deleteForThread(clientThreadId: String)
}
