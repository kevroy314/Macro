package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "macro_presets")
data class MacroPreset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val proteinG: Int = 0,
    val carbsG: Int = 0,
    val fatG: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),

    /** "manual" for presets the user made, "ai" for ones an estimation job created. */
    val source: String = SOURCE_MANUAL,
    /** Position under manual sorting. Ties fall back to name. */
    val sortOrder: Int = 0,
    val lastUsedAt: Long = 0,
    /**
     * Comma-separated keywords for search — "snack, salty, crunchy" for Goldfish.
     * Generated in the background by the daemon so that searching by meaning costs
     * nothing at the keystroke.
     */
    val searchTags: String = ""
) {
    val calories: Int
        get() = (proteinG * 4) + (carbsG * 4) + (fatG * 9)

    val isAi: Boolean
        get() = source == SOURCE_AI

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_AI = "ai"
    }
}
