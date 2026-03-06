package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "macro_targets")
data class MacroTarget(
    @PrimaryKey
    val id: Int = 1, // Singleton pattern - only one target config
    val proteinG: Int = 180,
    val carbsG: Int = 250,
    val fatG: Int = 70
) {
    val caloriesTarget: Int
        get() = (proteinG * 4) + (carbsG * 4) + (fatG * 9)
}
