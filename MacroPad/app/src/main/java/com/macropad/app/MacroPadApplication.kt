package com.macropad.app

import android.app.Application
import com.macropad.app.data.MacroPadDatabase
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.sync.DropboxManager

class MacroPadApplication : Application() {
    val database by lazy { MacroPadDatabase.getDatabase(this) }
    val repository by lazy {
        MacroRepository(
            database.dailyMacroDao(),
            database.macroPresetDao(),
            database.macroTargetDao(),
            database.widgetSettingsDao(),
            database.macroEntryDao(),
            database.syncSettingsDao()
        )
    }
    val dropboxManager by lazy { DropboxManager(this) }
}
