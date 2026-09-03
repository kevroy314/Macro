package com.macropad.app

import android.app.Application
import com.macropad.app.ai.AiNotifications
import com.macropad.app.ai.AiSyncManager
import com.macropad.app.ai.AppUpdater
import com.macropad.app.ai.PlanningManager
import com.macropad.app.ai.PresetTagger
import com.macropad.app.data.MacroPadDatabase
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.sync.DropboxMigration
import com.macropad.app.sync.DropboxManager
import com.macropad.app.sync.ServerBackup

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
    }
}
