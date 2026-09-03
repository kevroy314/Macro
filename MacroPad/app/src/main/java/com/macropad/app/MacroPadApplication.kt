package com.macropad.app

import android.app.Application
import com.macropad.app.ai.AiNotifications
import com.macropad.app.ai.AiSyncManager
import com.macropad.app.ai.AppUpdater
import com.macropad.app.ai.PlanningManager
import com.macropad.app.ai.PresetTagger
import androidx.room.InvalidationTracker
import com.macropad.app.data.MacroPadDatabase
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.sync.DropboxMigration
import com.macropad.app.sync.DropboxManager
import com.macropad.app.sync.ServerBackup
import com.macropad.app.sync.ServerBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MacroPadApplication : Application() {
    val database by lazy { MacroPadDatabase.getDatabase(this) }
    val repository by lazy {
        MacroRepository(
            database.dailyMacroDao(),
            database.macroPresetDao(),
            database.macroTargetDao(),
            database.widgetSettingsDao(),
            database.macroEntryDao(),
            database.syncSettingsDao(),
            database.aiJobDao(),
            database.aiSettingsDao(),
            database.presetUsageDao(),
            database.presetDisplaySettingsDao(),
            database.aiThreadDao(),
            database.aiThreadMessageDao()
        )
    }
    val dropboxManager by lazy { DropboxManager(this) }
    val aiSyncManager by lazy { AiSyncManager(this, repository) }
    val appUpdater by lazy { AppUpdater(this, repository) }
    val planningManager by lazy { PlanningManager(this, repository) }
    val presetTagger by lazy { PresetTagger(repository) }
    val serverBackup by lazy { ServerBackup(repository) }
    val dropboxMigration by lazy { DropboxMigration(repository, dropboxManager, serverBackup) }

    override fun onCreate() {
        super.onCreate()
        AiNotifications.createChannels(this)

        // WorkManager keeps periodic work across restarts, but re-declaring it here
        // means a reinstall or a cleared job store still ends up matching the setting.
        applicationScope.launch {
            val settings = repository.getAiSettings()
            val on = settings.autoBackup && settings.isConfigured
            setAutoBackup(on)
        }
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Queue a backup whenever the data actually changes.
     *
     * Room's invalidation tracker is the one place that sees every write to these
     * tables, whoever made it — the app, a widget tap, an AI estimate landing. Hooking
     * each repository method instead would mean the next new write path silently
     * stops being backed up.
     *
     * The upload itself only touches ai_settings, which is not observed here, so this
     * cannot feed itself.
     */
    private var watchingForChanges = false

    /** Turn the daily timer and the change trigger on or off together. */
    fun setAutoBackup(on: Boolean) {
        ServerBackupWorker.sync(this, on)
        if (on) watchForChanges()
    }

    @Synchronized
    private fun watchForChanges() {
        // Registering twice would queue two uploads per change.
        if (watchingForChanges) return
        watchingForChanges = true
        database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(
                arrayOf(
                    "daily_macros",
                    "macro_entries",
                    "macro_presets",
                    "macro_targets",
                    "preset_display_settings"
                )
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    ServerBackupWorker.afterChange(this@MacroPadApplication)
                }
            }
        )
    }
}
