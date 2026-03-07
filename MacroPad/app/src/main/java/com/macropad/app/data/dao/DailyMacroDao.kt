package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.DailyMacro
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyMacroDao {
    @Query("SELECT * FROM daily_macros WHERE date = :date")
    suspend fun getByDate(date: String): DailyMacro?

    @Query("SELECT * FROM daily_macros WHERE date = :date")
    fun getByDateFlow(date: String): Flow<DailyMacro?>

    @Query("SELECT * FROM daily_macros ORDER BY date DESC")
    fun getAllFlow(): Flow<List<DailyMacro>>

    @Query("SELECT * FROM daily_macros WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getInRange(startDate: String, endDate: String): Flow<List<DailyMacro>>

    @Query("SELECT * FROM daily_macros ORDER BY date DESC")
    suspend fun getAll(): List<DailyMacro>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dailyMacro: DailyMacro)

    @Query("UPDATE daily_macros SET proteinG = MAX(0, proteinG + :protein), carbsG = MAX(0, carbsG + :carbs), fatG = MAX(0, fatG + :fat), updatedAt = :timestamp WHERE date = :date")
    suspend fun addMacros(date: String, protein: Int, carbs: Int, fat: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE daily_macros SET annotation = :annotation, updatedAt = :timestamp WHERE date = :date")
    suspend fun updateAnnotation(date: String, annotation: String?, timestamp: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(dailyMacro: DailyMacro)
}
