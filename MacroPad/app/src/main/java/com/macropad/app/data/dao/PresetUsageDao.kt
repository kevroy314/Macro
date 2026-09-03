package com.macropad.app.data.dao

import androidx.room.*
import com.macropad.app.data.entity.PresetUsage
import kotlinx.coroutines.flow.Flow

/** Counts of preset applications within a rolling window. */
data class PresetUseCount(val presetId: Long, val uses: Int)

@Dao
interface PresetUsageDao {
    @Insert
    suspend fun insert(usage: PresetUsage)

    @Query(
        """SELECT presetId, COUNT(*) AS uses FROM preset_usages
           WHERE usedAt >= :since GROUP BY presetId"""
    )
    fun getCountsSinceFlow(since: Long): Flow<List<PresetUseCount>>

    @Query(
        """SELECT presetId, COUNT(*) AS uses FROM preset_usages
           WHERE usedAt >= :since GROUP BY presetId"""
    )
    suspend fun getCountsSince(since: Long): List<PresetUseCount>

    /** Housekeeping: usage older than the window can never affect the ordering. */
    @Query("DELETE FROM preset_usages WHERE usedAt < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query("DELETE FROM preset_usages WHERE presetId = :presetId")
    suspend fun deleteForPreset(presetId: Long)
}
