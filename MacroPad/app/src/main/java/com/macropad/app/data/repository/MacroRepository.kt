package com.macropad.app.data.repository

import com.macropad.app.data.dao.DailyMacroDao
import com.macropad.app.data.dao.MacroEntryDao
import com.macropad.app.data.dao.MacroPresetDao
import com.macropad.app.data.dao.MacroTargetDao
import com.macropad.app.data.dao.SyncSettingsDao
import com.macropad.app.data.dao.WidgetSettingsDao
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntry
import com.macropad.app.data.entity.MacroEntryGroup
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.SyncSettings
import com.macropad.app.data.entity.WidgetSettings
import com.macropad.app.sync.BackupData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class MacroRepository(
    private val dailyMacroDao: DailyMacroDao,
    private val presetDao: MacroPresetDao,
    private val targetDao: MacroTargetDao,
    private val widgetSettingsDao: WidgetSettingsDao,
    private val macroEntryDao: MacroEntryDao,
    private val syncSettingsDao: SyncSettingsDao
) {
    // Daily Macros
    fun getTodayMacrosFlow(): Flow<DailyMacro?> = dailyMacroDao.getByDateFlow(DailyMacro.today())

    fun getAllMacrosFlow(): Flow<List<DailyMacro>> = dailyMacroDao.getAllFlow()

    fun getMacrosInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacro>> =
        dailyMacroDao.getInRange(startDate.toString(), endDate.toString())

    suspend fun getTodayMacros(): DailyMacro? = dailyMacroDao.getByDate(DailyMacro.today())

    suspend fun getOrCreateToday(): DailyMacro {
        val today = DailyMacro.today()
        return dailyMacroDao.getByDate(today) ?: DailyMacro(date = today).also {
            dailyMacroDao.upsert(it)
        }
    }

    suspend fun addMacros(protein: Int = 0, carbs: Int = 0, fat: Int = 0, source: String = "manual") {
        val today = DailyMacro.today()

        // Record the entry for history tracking
        macroEntryDao.insert(
            MacroEntry(
                date = today,
                proteinG = protein,
                carbsG = carbs,
                fatG = fat,
                source = source
            )
        )

        // Update daily totals
        val existing = dailyMacroDao.getByDate(today)
        if (existing == null) {
            dailyMacroDao.upsert(DailyMacro(date = today, proteinG = protein, carbsG = carbs, fatG = fat))
        } else {
            dailyMacroDao.addMacros(today, protein, carbs, fat)
        }
    }

    suspend fun setMacros(protein: Int, carbs: Int, fat: Int) {
        val today = DailyMacro.today()
        val existing = dailyMacroDao.getByDate(today)
        dailyMacroDao.upsert(
            existing?.copy(proteinG = protein, carbsG = carbs, fatG = fat, updatedAt = System.currentTimeMillis())
                ?: DailyMacro(date = today, proteinG = protein, carbsG = carbs, fatG = fat)
        )
    }

    suspend fun updateAnnotation(date: String, annotation: String?) {
        val existing = dailyMacroDao.getByDate(date)
        if (existing != null) {
            dailyMacroDao.updateAnnotation(date, annotation)
        } else {
            dailyMacroDao.upsert(DailyMacro(date = date, annotation = annotation))
        }
    }

    suspend fun getAllMacros(): List<DailyMacro> = dailyMacroDao.getAll()

    suspend fun importMacros(macros: List<DailyMacro>) {
        macros.forEach { dailyMacroDao.upsert(it) }
    }

    // Macro Entries (individual additions)
    fun getTodayEntriesFlow(): Flow<List<MacroEntry>> =
        macroEntryDao.getEntriesForDateFlow(DailyMacro.today())

    fun getEntriesForDateFlow(date: String): Flow<List<MacroEntry>> =
        macroEntryDao.getEntriesForDateFlow(date)

    /**
     * Get entries grouped by 5-minute windows with running totals for burnup chart
     */
    fun getGroupedEntriesFlow(date: String): Flow<List<MacroEntryGroup>> =
        macroEntryDao.getEntriesForDateFlow(date).map { entries ->
            groupEntries(entries)
        }

    private fun groupEntries(entries: List<MacroEntry>): List<MacroEntryGroup> {
        if (entries.isEmpty()) return emptyList()

        val groups = mutableListOf<MacroEntryGroup>()
        val sortedEntries = entries.sortedBy { it.timestamp }

        var currentGroup = mutableListOf<MacroEntry>()
        var groupStartTime = sortedEntries.first().timestamp

        var runningProtein = 0
        var runningCarbs = 0
        var runningFat = 0

        for (entry in sortedEntries) {
            if (currentGroup.isEmpty() ||
                entry.timestamp - groupStartTime <= MacroEntry.GROUPING_WINDOW_MS) {
                currentGroup.add(entry)
            } else {
                // Finish current group
                val totalP = currentGroup.sumOf { it.proteinG }
                val totalC = currentGroup.sumOf { it.carbsG }
                val totalF = currentGroup.sumOf { it.fatG }
                runningProtein += totalP
                runningCarbs += totalC
                runningFat += totalF

                groups.add(
                    MacroEntryGroup(
                        startTime = groupStartTime,
                        endTime = currentGroup.last().timestamp,
                        entries = currentGroup.toList(),
                        totalProtein = totalP,
                        totalCarbs = totalC,
                        totalFat = totalF,
                        runningProtein = runningProtein,
                        runningCarbs = runningCarbs,
                        runningFat = runningFat
                    )
                )

                // Start new group
                currentGroup = mutableListOf(entry)
                groupStartTime = entry.timestamp
            }
        }

        // Don't forget the last group
        if (currentGroup.isNotEmpty()) {
            val totalP = currentGroup.sumOf { it.proteinG }
            val totalC = currentGroup.sumOf { it.carbsG }
            val totalF = currentGroup.sumOf { it.fatG }
            runningProtein += totalP
            runningCarbs += totalC
            runningFat += totalF

            groups.add(
                MacroEntryGroup(
                    startTime = groupStartTime,
                    endTime = currentGroup.last().timestamp,
                    entries = currentGroup.toList(),
                    totalProtein = totalP,
                    totalCarbs = totalC,
                    totalFat = totalF,
                    runningProtein = runningProtein,
                    runningCarbs = runningCarbs,
                    runningFat = runningFat
                )
            )
        }

        return groups
    }

    suspend fun getAllEntries(): List<MacroEntry> = macroEntryDao.getAll()

    // Presets
    fun getAllPresetsFlow(): Flow<List<MacroPreset>> = presetDao.getAllFlow()

    suspend fun getAllPresets(): List<MacroPreset> = presetDao.getAll()

    suspend fun getPresetById(id: Long): MacroPreset? = presetDao.getById(id)

    suspend fun savePreset(preset: MacroPreset): Long = presetDao.upsert(preset)

    suspend fun deletePreset(preset: MacroPreset) = presetDao.delete(preset)

    suspend fun deletePresetById(id: Long) = presetDao.deleteById(id)

    suspend fun applyPreset(preset: MacroPreset) {
        addMacros(preset.proteinG, preset.carbsG, preset.fatG, source = "preset:${preset.name}")
    }

    suspend fun importPresets(presets: List<MacroPreset>) {
        presets.forEach { presetDao.upsert(it.copy(id = 0)) } // Reset IDs to allow auto-generation
    }

    // Targets
    fun getTargetFlow(): Flow<MacroTarget?> = targetDao.getTargetFlow()

    suspend fun getTarget(): MacroTarget = targetDao.getTarget() ?: MacroTarget()

    suspend fun saveTarget(target: MacroTarget) = targetDao.upsert(target)

    // Widget Settings
    fun getWidgetSettingsFlow(): Flow<WidgetSettings?> = widgetSettingsDao.getSettingsFlow()

    suspend fun getWidgetSettings(): WidgetSettings = widgetSettingsDao.getSettings() ?: WidgetSettings()

    suspend fun saveWidgetSettings(settings: WidgetSettings) = widgetSettingsDao.upsert(settings)

    // Sync Settings
    fun getSyncSettingsFlow(): Flow<SyncSettings?> = syncSettingsDao.getSettingsFlow()

    suspend fun getSyncSettings(): SyncSettings = syncSettingsDao.getSettings() ?: SyncSettings()

    suspend fun saveSyncSettings(settings: SyncSettings) = syncSettingsDao.upsert(settings)

    // Backup/Restore for Dropbox sync
    suspend fun createBackup(): BackupData {
        return BackupData(
            version = 3,
            exportDate = java.time.LocalDateTime.now().toString(),
            deviceId = android.os.Build.MODEL,
            targets = getTarget(),
            widgetSettings = getWidgetSettings(),
            presets = getAllPresets(),
            dailyMacros = getAllMacros()
        )
    }

    suspend fun importBackup(backup: BackupData) {
        // Import targets
        backup.targets?.let { saveTarget(it) }

        // Import widget settings
        backup.widgetSettings?.let { saveWidgetSettings(it) }

        // Import presets (merge - add new ones)
        backup.presets.forEach { preset ->
            presetDao.upsert(preset.copy(id = 0))
        }

        // Import daily macros (merge - update existing, add new)
        backup.dailyMacros.forEach { macro ->
            dailyMacroDao.upsert(macro)
        }
    }

    /**
     * Check if local data is empty (for new app setup scenario)
     */
    suspend fun isLocalDataEmpty(): Boolean {
        return getAllMacros().isEmpty() && getAllPresets().isEmpty()
    }
}
