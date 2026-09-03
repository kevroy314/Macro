package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.PresetDisplaySettings
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDisplaySettingsDao {
    @Query("SELECT * FROM preset_display_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<PresetDisplaySettings?>

    @Query("SELECT * FROM preset_display_settings WHERE id = 1")
    suspend fun getSettings(): PresetDisplaySettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: PresetDisplaySettings)
}
