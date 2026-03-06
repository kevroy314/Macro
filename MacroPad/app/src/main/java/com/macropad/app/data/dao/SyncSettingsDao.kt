package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.SyncSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSettingsDao {
    @Query("SELECT * FROM sync_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<SyncSettings?>

    @Query("SELECT * FROM sync_settings WHERE id = 1")
    suspend fun getSettings(): SyncSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SyncSettings)
}
