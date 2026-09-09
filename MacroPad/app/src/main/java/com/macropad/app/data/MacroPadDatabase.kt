package com.macropad.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.macropad.app.data.dao.AiJobDao
import com.macropad.app.data.dao.AiSettingsDao
import com.macropad.app.data.dao.AiThreadDao
import com.macropad.app.data.dao.AiThreadMessageDao
import com.macropad.app.data.dao.DailyMacroDao
import com.macropad.app.data.dao.MacroEntryDao
import com.macropad.app.data.dao.MacroPresetDao
import com.macropad.app.data.dao.MacroTargetDao
import com.macropad.app.data.dao.PresetDisplaySettingsDao
import com.macropad.app.data.dao.PresetUsageDao
import com.macropad.app.data.dao.SyncSettingsDao
import com.macropad.app.data.dao.WidgetSettingsDao
import com.macropad.app.data.entity.AiJob
import com.macropad.app.data.entity.AiSettings
import com.macropad.app.data.entity.AiThread
import com.macropad.app.data.entity.AiThreadMessage
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntry
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.PresetDisplaySettings
import com.macropad.app.data.entity.PresetUsage
import com.macropad.app.data.entity.SyncSettings
import com.macropad.app.data.entity.WidgetSettings

@Database(
    entities = [
        DailyMacro::class,
        MacroPreset::class,
        MacroTarget::class,
        WidgetSettings::class,
        MacroEntry::class,
        SyncSettings::class,
        AiJob::class,
        AiSettings::class,
        PresetUsage::class,
        PresetDisplaySettings::class,
        AiThread::class,
        AiThreadMessage::class
    ],
    version = 16,
    exportSchema = false
)
abstract class MacroPadDatabase : RoomDatabase() {
    abstract fun dailyMacroDao(): DailyMacroDao
    abstract fun macroPresetDao(): MacroPresetDao
    abstract fun macroTargetDao(): MacroTargetDao
    abstract fun widgetSettingsDao(): WidgetSettingsDao
    abstract fun macroEntryDao(): MacroEntryDao
    abstract fun syncSettingsDao(): SyncSettingsDao
    abstract fun aiJobDao(): AiJobDao
    abstract fun aiSettingsDao(): AiSettingsDao
    abstract fun presetUsageDao(): PresetUsageDao
    abstract fun presetDisplaySettingsDao(): PresetDisplaySettingsDao
    abstract fun aiThreadDao(): AiThreadDao
    abstract fun aiThreadMessageDao(): AiThreadMessageDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // AI estimation jobs
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_jobs (
                        clientJobId TEXT NOT NULL PRIMARY KEY,
                        serverJobId TEXT,
                        parentJobId TEXT,
                        status TEXT NOT NULL DEFAULT 'pending_upload',
                        promptText TEXT NOT NULL DEFAULT '',
                        imagePaths TEXT NOT NULL DEFAULT '[]',
                        thresholdMode TEXT NOT NULL DEFAULT 'percent',
                        thresholdValue REAL NOT NULL DEFAULT 10,
                        resultJson TEXT,
                        revision INTEGER NOT NULL DEFAULT 0,
                        error TEXT,
                        appliedProteinG INTEGER NOT NULL DEFAULT 0,
                        appliedCarbsG INTEGER NOT NULL DEFAULT 0,
                        appliedFatG INTEGER NOT NULL DEFAULT 0,
                        appliedEntryId INTEGER,
                        presetId INTEGER,
                        questionsResolved INTEGER NOT NULL DEFAULT 0,
                        pendingAnswersJson TEXT NOT NULL DEFAULT '{}',
                        excludedFromTotals INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        serverUpdatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_jobs_status ON ai_jobs(status)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        enabled INTEGER NOT NULL DEFAULT 0,
                        serverUrl TEXT NOT NULL DEFAULT '',
                        apiKey TEXT NOT NULL DEFAULT '',
                        thresholdMode TEXT NOT NULL DEFAULT 'PERCENT',
                        thresholdValue REAL NOT NULL DEFAULT 10,
                        autoApply INTEGER NOT NULL DEFAULT 1,
                        lastPolledServerTime INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Rolling-window preset usage
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS preset_usages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        presetId INTEGER NOT NULL,
                        usedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_preset_usages_usedAt ON preset_usages(usedAt)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS preset_display_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        sortMode TEXT NOT NULL DEFAULT 'ALPHABETICAL',
                        splitAiAndManual INTEGER NOT NULL DEFAULT 0,
                        aiOnTop INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Presets gain provenance and an explicit order
                db.execSQL("ALTER TABLE macro_presets ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE macro_presets ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE macro_presets ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
                // Seed manual order from the alphabetical order the user sees today.
                // NOCASE matches the case-insensitive sort the app applies in Kotlin.
                db.execSQL("""
                    UPDATE macro_presets SET sortOrder = (
                        SELECT COUNT(*) FROM macro_presets AS other
                        WHERE other.name COLLATE NOCASE < macro_presets.name COLLATE NOCASE
                    )
                """.trimIndent())

                // Entries gain soft delete and a link back to the job that made them
                db.execSQL("ALTER TABLE macro_entries ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE macro_entries ADD COLUMN aiJobId TEXT")
                db.execSQL("ALTER TABLE macro_entries ADD COLUMN note TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_threads (
                        clientThreadId TEXT NOT NULL PRIMARY KEY,
                        serverThreadId TEXT,
                        title TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'idle',
                        error TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        serverUpdatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_thread_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        clientThreadId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        text TEXT NOT NULL DEFAULT '',
                        imageCount INTEGER NOT NULL DEFAULT 0,
                        proposalsJson TEXT NOT NULL DEFAULT '[]',
                        appliedIndicesJson TEXT NOT NULL DEFAULT '[]',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_thread_messages_clientThreadId " +
                        "ON ai_thread_messages(clientThreadId)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE macro_presets ADD COLUMN searchTags TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * The SHA-256 pin of a self-signed certificate, for a daemon reachable only
         * on the home network where no public authority will issue one.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_settings ADD COLUMN certPin TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Daily automatic backup to the user's own server. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_settings ADD COLUMN autoBackup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_settings ADD COLUMN lastAutoBackupAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Turn daily backup on for installs that already have a server.
         *
         * The default in the entity only reaches rows created after it, and everyone
         * already using the AI features has a row. Deliberately scoped to configured
         * installs: switching it on where there is no server to back up to would
         * schedule work that can never succeed.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE ai_settings SET autoBackup = 1 " +
                        "WHERE serverUrl != '' AND apiKey != ''"
                )
            }
        }

        /** A line of progress to show while a planning turn is thinking. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_threads ADD COLUMN progress TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Live progress for estimates, matching what planning threads already had. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_jobs ADD COLUMN progress TEXT NOT NULL DEFAULT ''")
            }
        }

        /** The steps an estimate or a planning turn took, kept after it finishes. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_jobs ADD COLUMN steps TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE ai_threads ADD COLUMN steps TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** Carry an update's notes across the install, to show once it is running. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_settings ADD COLUMN lastUpdateNotes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ai_settings ADD COLUMN lastUpdateVersionCode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Steps belong to the reply they produced, not to the whole conversation. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_thread_messages ADD COLUMN steps TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * Count alcohol as carbohydrate, and remember whether the walkthrough was shown.
         *
         * `onboardingSeen` is set to 1 here on purpose. A migration only runs on an
         * upgrade, never on a fresh install, so this is exactly the line that stops
         * everyone already using the app from being shown a first-run tour.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_settings ADD COLUMN alcoholAsCarbs INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE widget_settings ADD COLUMN onboardingSeen INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE widget_settings SET onboardingSeen = 1")
            }
        }

        fun getDatabase(context: Context): MacroPadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MacroPadDatabase::class.java,
                    "macropad_database"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16
                )
                // Deliberately NOT fallbackToDestructiveMigration(). This database is
                // the only copy of months of the user's food log; a migration bug
                // should crash loudly here, not silently delete it. Verify schema
                // changes with tools/verify_migrations.py before shipping.
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
