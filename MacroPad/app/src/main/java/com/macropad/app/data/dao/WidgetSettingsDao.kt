package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.WidgetSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetSettingsDao {
    @Query("SELECT * FROM widget_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<WidgetSettings?>

    @Query("SELECT * FROM widget_settings WHERE id = 1")
    suspend fun getSettings(): WidgetSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: WidgetSettings)
}
