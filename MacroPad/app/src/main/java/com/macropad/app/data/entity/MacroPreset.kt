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
    val createdAt: Long = System.currentTimeMillis()
) {
    val calories: Int
        get() = (proteinG * 4) + (carbsG * 4) + (fatG * 9)
}
