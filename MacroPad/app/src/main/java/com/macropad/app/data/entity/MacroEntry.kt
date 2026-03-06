package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Individual macro entry with timestamp for tracking additions throughout the day.
 * Entries within 5 minutes are grouped together in the UI.
 */
@Entity(tableName = "macro_entries")
data class MacroEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String, // ISO date YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val proteinG: Int = 0,
    val carbsG: Int = 0,
    val fatG: Int = 0,
    val source: String = "manual" // "manual", "preset", "widget"
) {
    val calories: Int
        get() = (proteinG * 4) + (carbsG * 4) + (fatG * 9)

    companion object {
        const val GROUPING_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }
}

/**
 * Grouped entries for display - entries within 5 min window
 */
data class MacroEntryGroup(
    val startTime: Long,
    val endTime: Long,
    val entries: List<MacroEntry>,
    val totalProtein: Int,
    val totalCarbs: Int,
    val totalFat: Int,
    val runningProtein: Int, // cumulative at end of group
    val runningCarbs: Int,
    val runningFat: Int
) {
    val totalCalories: Int
        get() = (totalProtein * 4) + (totalCarbs * 4) + (totalFat * 9)

    val runningCalories: Int
        get() = (runningProtein * 4) + (runningCarbs * 4) + (runningFat * 9)
}
