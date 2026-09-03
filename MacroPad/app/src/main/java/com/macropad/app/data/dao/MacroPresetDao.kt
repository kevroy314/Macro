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

    @Query("UPDATE macro_presets SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE macro_presets SET lastUsedAt = :usedAt WHERE id = :id")
    suspend fun updateLastUsed(id: Long, usedAt: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM macro_presets")
    suspend fun getMaxSortOrder(): Int

    @Query("SELECT * FROM macro_presets WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): MacroPreset?

    @Query("UPDATE macro_presets SET searchTags = :tags WHERE id = :id")
    suspend fun updateSearchTags(id: Long, tags: String)

    @Query("SELECT * FROM macro_presets WHERE searchTags = ''")
    suspend fun getUntagged(): List<MacroPreset>

    @Query("DELETE FROM macro_presets")
    suspend fun deleteAll()
}
