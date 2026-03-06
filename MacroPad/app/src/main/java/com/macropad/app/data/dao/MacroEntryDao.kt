package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.MacroEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroEntryDao {
    @Query("SELECT * FROM macro_entries WHERE date = :date ORDER BY timestamp ASC")
    fun getEntriesForDateFlow(date: String): Flow<List<MacroEntry>>

    @Query("SELECT * FROM macro_entries WHERE date = :date ORDER BY timestamp ASC")
    suspend fun getEntriesForDate(date: String): List<MacroEntry>

    @Query("SELECT * FROM macro_entries ORDER BY timestamp DESC")
    suspend fun getAll(): List<MacroEntry>

    @Insert
    suspend fun insert(entry: MacroEntry): Long

    @Delete
    suspend fun delete(entry: MacroEntry)

    @Query("DELETE FROM macro_entries WHERE date = :date")
    suspend fun deleteAllForDate(date: String)
}
