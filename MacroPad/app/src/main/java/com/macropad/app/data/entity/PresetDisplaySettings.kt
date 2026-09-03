package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How the preset list is ordered and grouped, in the app and in the preset widget.
 */
@Entity(tableName = "preset_display_settings")
data class PresetDisplaySettings(
    @PrimaryKey
    val id: Int = 1, // Singleton pattern

    val sortMode: PresetSortMode = PresetSortMode.ALPHABETICAL,

    /** Keep AI-created presets in their own group rather than interleaved. */
    val splitAiAndManual: Boolean = false,
    /** Which group comes first when they are split. */
    val aiOnTop: Boolean = false
)

enum class PresetSortMode {
    /** By name, A-Z. */
    ALPHABETICAL,

    /** Most-applied over the last 7 days first. */
    MOST_USED_WEEK,

    /** Whatever you reached for last, first. */
    RECENTLY_USED,

    /** Whatever order the user dragged them into. */
    MANUAL
}
