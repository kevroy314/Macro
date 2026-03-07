package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "widget_settings")
data class WidgetSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton pattern

    // Increment values (default +5)
    val proteinIncrement: Int = 5,
    val carbsIncrement: Int = 5,
    val fatIncrement: Int = 5,

    // Decrement values (default -1)
    val proteinDecrement: Int = 1,
    val carbsDecrement: Int = 1,
    val fatDecrement: Int = 1,

    // Day reset hour (0-23, default 0 = midnight)
    // If set to 5, the day resets at 5am instead of midnight
    val dayResetHour: Int = 0
)
