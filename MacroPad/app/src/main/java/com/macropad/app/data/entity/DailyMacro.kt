package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "daily_macros")
data class DailyMacro(
    @PrimaryKey
    val date: String, // ISO date format YYYY-MM-DD
    val proteinG: Int = 0,
    val carbsG: Int = 0,
    val fatG: Int = 0,
    val annotation: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val calories: Int
        get() = (proteinG * 4) + (carbsG * 4) + (fatG * 9)

    companion object {
        fun today(): String = LocalDate.now().toString()

        fun forDate(date: LocalDate): String = date.toString()
    }
}
