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

    /**
     * Undo target. Hidden entries are skipped: their macros have already been taken
     * back out of the day's totals, so undoing one would subtract them twice.
     */
    @Query("SELECT * FROM macro_entries WHERE date = :date AND hidden = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastEntryForDate(date: String): MacroEntry?

    @Query("DELETE FROM macro_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM macro_entries WHERE id = :id")
    suspend fun getById(id: Long): MacroEntry?

    @Query("UPDATE macro_entries SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE macro_entries SET proteinG = :protein, carbsG = :carbs, fatG = :fat WHERE id = :id")
    suspend fun updateMacros(id: Long, protein: Int, carbs: Int, fat: Int)

    @Query("SELECT * FROM macro_entries WHERE aiJobId = :aiJobId ORDER BY id DESC LIMIT 1")
    suspend fun getByAiJobId(aiJobId: String): MacroEntry?

    @Query("SELECT * FROM macro_entries WHERE date = :date AND hidden = 0 ORDER BY timestamp ASC")
    fun getVisibleEntriesForDateFlow(date: String): Flow<List<MacroEntry>>
}
