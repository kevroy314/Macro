package com.macropad.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.macropad.app.data.dao.DailyMacroDao
import com.macropad.app.data.dao.MacroEntryDao
import com.macropad.app.data.dao.MacroPresetDao
import com.macropad.app.data.dao.MacroTargetDao
import com.macropad.app.data.dao.SyncSettingsDao
import com.macropad.app.data.dao.WidgetSettingsDao
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntry
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.SyncSettings
import com.macropad.app.data.entity.WidgetSettings

@Database(
    entities = [DailyMacro::class, MacroPreset::class, MacroTarget::class, WidgetSettings::class, MacroEntry::class, SyncSettings::class],
    version = 4,
    exportSchema = false
)
abstract class MacroPadDatabase : RoomDatabase() {
    abstract fun dailyMacroDao(): DailyMacroDao
    abstract fun macroPresetDao(): MacroPresetDao
    abstract fun macroTargetDao(): MacroTargetDao
    abstract fun widgetSettingsDao(): WidgetSettingsDao
    abstract fun macroEntryDao(): MacroEntryDao
    abstract fun syncSettingsDao(): SyncSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: MacroPadDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS widget_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        proteinIncrement INTEGER NOT NULL DEFAULT 5,
                        carbsIncrement INTEGER NOT NULL DEFAULT 5,
                        fatIncrement INTEGER NOT NULL DEFAULT 5,
                        proteinDecrement INTEGER NOT NULL DEFAULT 1,
                        carbsDecrement INTEGER NOT NULL DEFAULT 1,
                        fatDecrement INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS macro_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        proteinG INTEGER NOT NULL DEFAULT 0,
                        carbsG INTEGER NOT NULL DEFAULT 0,
                        fatG INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'manual'
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_macro_entries_date ON macro_entries(date)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        conflictResolution TEXT NOT NULL DEFAULT 'LOCAL_WINS',
                        autoSyncEnabled INTEGER NOT NULL DEFAULT 0,
                        lastSyncTimestamp INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): MacroPadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MacroPadDatabase::class.java,
                    "macropad_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
