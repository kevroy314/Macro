package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.MacroPreset
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroPresetDao {
    @Query("SELECT * FROM macro_presets ORDER BY name ASC")
    fun getAllFlow(): Flow<List<MacroPreset>>

    @Query("SELECT * FROM macro_presets ORDER BY name ASC")
    suspend fun getAll(): List<MacroPreset>

    @Query("SELECT * FROM macro_presets WHERE id = :id")
    suspend fun getById(id: Long): MacroPreset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: MacroPreset): Long

    @Delete
    suspend fun delete(preset: MacroPreset)

    @Query("DELETE FROM macro_presets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
