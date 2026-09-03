package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per preset application, so "most used this week" is a rolling window
 * rather than a counter that can only ever grow.
 */
@Entity(tableName = "preset_usages", indices = [Index("usedAt")])
data class PresetUsage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val presetId: Long,
    val usedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }
}
