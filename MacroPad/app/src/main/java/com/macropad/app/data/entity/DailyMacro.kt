package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

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
        /**
         * Get "today" date string, adjusted for day reset hour.
         * If dayResetHour is 5 and current time is 3am, returns yesterday's date.
         * If dayResetHour is 5 and current time is 6am, returns today's date.
         */
        fun today(dayResetHour: Int = 0): String {
            val now = LocalDate.now()
            val currentHour = LocalTime.now().hour

            // If we're before the reset hour, we're still on "yesterday"
            return if (dayResetHour > 0 && currentHour < dayResetHour) {
                now.minusDays(1).toString()
            } else {
                now.toString()
            }
        }

        fun forDate(date: LocalDate): String = date.toString()
    }
}
