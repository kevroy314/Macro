package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.AiSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSettingsDao {
    @Query("SELECT * FROM ai_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<AiSettings?>

    @Query("SELECT * FROM ai_settings WHERE id = 1")
    suspend fun getSettings(): AiSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AiSettings)
}
