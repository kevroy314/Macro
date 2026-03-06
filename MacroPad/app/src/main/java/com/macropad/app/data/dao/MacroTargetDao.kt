package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.MacroTarget
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroTargetDao {
    @Query("SELECT * FROM macro_targets WHERE id = 1")
    fun getTargetFlow(): Flow<MacroTarget?>

    @Query("SELECT * FROM macro_targets WHERE id = 1")
    suspend fun getTarget(): MacroTarget?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: MacroTarget)
}
