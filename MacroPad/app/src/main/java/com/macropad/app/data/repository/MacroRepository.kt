package com.macropad.app.data.repository

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
import com.macropad.app.data.entity.AiProposal
import com.macropad.app.data.entity.AiResult
import com.macropad.app.data.entity.AiThread
import com.macropad.app.data.entity.AiThreadMessage
import com.macropad.app.data.entity.AiSettings
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntry
import com.macropad.app.data.entity.MacroEntryGroup
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.PresetDisplaySettings
import com.macropad.app.data.entity.PresetSortMode
import com.macropad.app.data.entity.PresetUsage
import com.macropad.app.data.entity.SyncSettings
import com.macropad.app.data.entity.WidgetSettings
import com.macropad.app.sync.BackupData
import com.macropad.app.sync.BackupMerge
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class MacroRepository(
    private val dailyMacroDao: DailyMacroDao,
    private val presetDao: MacroPresetDao,
    private val targetDao: MacroTargetDao,
    private val widgetSettingsDao: WidgetSettingsDao,
    private val macroEntryDao: MacroEntryDao,
    private val syncSettingsDao: SyncSettingsDao,
    private val aiJobDao: AiJobDao,
    private val aiSettingsDao: AiSettingsDao,
    private val presetUsageDao: PresetUsageDao,
    private val presetDisplaySettingsDao: PresetDisplaySettingsDao,
    private val aiThreadDao: AiThreadDao,
    private val aiThreadMessageDao: AiThreadMessageDao
) {
    private val gson = Gson()
    // Helper to get today's date with reset hour adjustment
    private suspend fun getTodayDate(): String {
        val settings = widgetSettingsDao.getSettings()
        return DailyMacro.today(settings?.dayResetHour ?: 0)
    }

    // Public version for callers that need the adjusted date
    suspend fun getAdjustedTodayDate(): String = getTodayDate()

    // Sync version for flows (uses default, will update when settings change)
    private fun getTodayDateSync(dayResetHour: Int = 0): String = DailyMacro.today(dayResetHour)

    // Daily Macros - reacts to dayResetHour changes
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getTodayMacrosFlow(): Flow<DailyMacro?> =
        widgetSettingsDao.getSettingsFlow().flatMapLatest { settings ->
            val today = getTodayDateSync(settings?.dayResetHour ?: 0)
            dailyMacroDao.getByDateFlow(today)
        }

    fun getAllMacrosFlow(): Flow<List<DailyMacro>> = dailyMacroDao.getAllFlow()

    fun getMacrosInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacro>> =
        dailyMacroDao.getInRange(startDate.toString(), endDate.toString())

    suspend fun getTodayMacros(): DailyMacro? = dailyMacroDao.getByDate(getTodayDate())

    suspend fun getOrCreateToday(): DailyMacro {
        val today = getTodayDate()
        return dailyMacroDao.getByDate(today) ?: DailyMacro(date = today).also {
            dailyMacroDao.upsert(it)
        }
    }

    suspend fun addMacros(protein: Int = 0, carbs: Int = 0, fat: Int = 0, source: String = "manual") {
        val today = getTodayDate()

        // Get existing values to ensure we don't go negative
        val existing = dailyMacroDao.getByDate(today)

        // Calculate actual changes, clamping to prevent negative totals
        val actualProtein = if (existing != null) {
            val newTotal = existing.proteinG + protein
            if (newTotal < 0) -existing.proteinG else protein
        } else {
            maxOf(0, protein)
        }

        val actualCarbs = if (existing != null) {
            val newTotal = existing.carbsG + carbs
            if (newTotal < 0) -existing.carbsG else carbs
        } else {
            maxOf(0, carbs)
        }

        val actualFat = if (existing != null) {
            val newTotal = existing.fatG + fat
            if (newTotal < 0) -existing.fatG else fat
        } else {
            maxOf(0, fat)
        }

        // Record the entry for history tracking (using actual amounts applied)
        macroEntryDao.insert(
            MacroEntry(
                date = today,
                proteinG = actualProtein,
                carbsG = actualCarbs,
                fatG = actualFat,
                source = source
            )
        )

        // Update daily totals
        if (existing == null) {
            dailyMacroDao.upsert(DailyMacro(date = today, proteinG = actualProtein, carbsG = actualCarbs, fatG = actualFat))
        } else {
            dailyMacroDao.addMacros(today, actualProtein, actualCarbs, actualFat)
        }
    }

    suspend fun setMacros(protein: Int, carbs: Int, fat: Int) {
        val today = getTodayDate()
        val existing = dailyMacroDao.getByDate(today)
        // Ensure non-negative values
        val safeProtein = maxOf(0, protein)
        val safeCarbs = maxOf(0, carbs)
        val safeFat = maxOf(0, fat)
        dailyMacroDao.upsert(
            existing?.copy(proteinG = safeProtein, carbsG = safeCarbs, fatG = safeFat, updatedAt = System.currentTimeMillis())
                ?: DailyMacro(date = today, proteinG = safeProtein, carbsG = safeCarbs, fatG = safeFat)
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getTodayEntriesFlow(): Flow<List<MacroEntry>> =
        widgetSettingsDao.getSettingsFlow().flatMapLatest { settings ->
            val today = getTodayDateSync(settings?.dayResetHour ?: 0)
            macroEntryDao.getEntriesForDateFlow(today)
        }

    fun getEntriesForDateFlow(date: String): Flow<List<MacroEntry>> =
        macroEntryDao.getEntriesForDateFlow(date)

    /**
     * Entries grouped into 5-minute windows with running totals, for the burnup
     * chart and the entry log.
     *
     * Hidden entries stay in the list so the log can show what was logged and then
     * excluded, but they are left out of every sum — the running total has to land
     * on the same number as the day's headline total.
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
                val counted = currentGroup.filter { !it.hidden }
                val totalP = counted.sumOf { it.proteinG }
                val totalC = counted.sumOf { it.carbsG }
                val totalF = counted.sumOf { it.fatG }
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
            val counted = currentGroup.filter { !it.hidden }
            val totalP = counted.sumOf { it.proteinG }
            val totalC = counted.sumOf { it.carbsG }
            val totalF = counted.sumOf { it.fatG }
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

    /**
     * Undo the last macro entry for today.
     * Returns the entry that was undone, or null if there was nothing to undo.
     */
    suspend fun undoLastEntry(): MacroEntry? {
        val today = getTodayDate()
        val lastEntry = macroEntryDao.getLastEntryForDate(today) ?: return null

        // Delete the entry
        macroEntryDao.deleteById(lastEntry.id)

        // Get current totals to clamp the subtraction (prevent negative values)
        val existing = dailyMacroDao.getByDate(today)
        if (existing != null) {
            // Calculate how much we can actually subtract without going negative
            val actualProteinDelta = minOf(lastEntry.proteinG, existing.proteinG)
            val actualCarbsDelta = minOf(lastEntry.carbsG, existing.carbsG)
            val actualFatDelta = minOf(lastEntry.fatG, existing.fatG)

            // Subtract from daily totals (clamped to prevent negative)
            dailyMacroDao.addMacros(today, -actualProteinDelta, -actualCarbsDelta, -actualFatDelta)
        }
        // If no existing record, nothing to subtract from

        return lastEntry
    }

    /**
     * Get the last entry for today (for showing undo info)
     */
    suspend fun getLastTodayEntry(): MacroEntry? = macroEntryDao.getLastEntryForDate(getTodayDate())

    // Presets
    fun getAllPresetsFlow(): Flow<List<MacroPreset>> = presetDao.getAllFlow()

    suspend fun getAllPresets(): List<MacroPreset> = presetDao.getAll()

    suspend fun getPresetById(id: Long): MacroPreset? = presetDao.getById(id)

    suspend fun savePreset(preset: MacroPreset): Long = presetDao.upsert(preset)

    suspend fun deletePreset(preset: MacroPreset) = presetDao.delete(preset)

    suspend fun deletePresetById(id: Long) = presetDao.deleteById(id)

    suspend fun applyPreset(preset: MacroPreset) {
        addMacros(preset.proteinG, preset.carbsG, preset.fatG, source = "preset:${preset.name}")
        recordPresetUse(preset.id)
    }

    /** Records an application so "most used this week" reflects widget taps too. */
    suspend fun recordPresetUse(presetId: Long) {
        if (presetId <= 0) return
        val now = System.currentTimeMillis()
        presetUsageDao.insert(PresetUsage(presetId = presetId, usedAt = now))
        presetDao.updateLastUsed(presetId, now)
        // Anything older than the window can never affect the ordering again.
        presetUsageDao.pruneOlderThan(now - PresetUsage.WINDOW_MS)
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

    /**
     * Whether to show the first-run walkthrough.
     *
     * The 15→16 migration marks existing installs as having seen it, since migrations
     * only run on upgrade. That covers everyone with a settings row — but a long-time
     * user who never opened Settings has no row to migrate, and a bare flag check
     * would show them a "welcome, here's how to start" tour over months of their own
     * data. So anyone with data already is treated as having seen it, and the flag is
     * written so this is decided once.
     */
    suspend fun shouldShowOnboarding(): Boolean {
        val settings = widgetSettingsDao.getSettings()
        if (settings?.onboardingSeen == true) return false

        val hasHistory = dailyMacroDao.getAll().isNotEmpty() ||
            presetDao.getAll().isNotEmpty() ||
            targetDao.getTarget() != null
        if (hasHistory) {
            markOnboardingSeen()
            return false
        }
        return true
    }

    suspend fun markOnboardingSeen() {
        val current = widgetSettingsDao.getSettings() ?: WidgetSettings()
        widgetSettingsDao.upsert(current.copy(onboardingSeen = true))
    }

    // Sync Settings
    fun getSyncSettingsFlow(): Flow<SyncSettings?> = syncSettingsDao.getSettingsFlow()

    suspend fun getSyncSettings(): SyncSettings = syncSettingsDao.getSettings() ?: SyncSettings()

    suspend fun saveSyncSettings(settings: SyncSettings) = syncSettingsDao.upsert(settings)

    // Backup/Restore for Dropbox sync
    suspend fun createBackup(): BackupData {
        return BackupData(
            version = BackupData.CURRENT_VERSION,
            exportDate = java.time.LocalDateTime.now().toString(),
            deviceId = android.os.Build.MODEL,
            targets = getTarget(),
            widgetSettings = getWidgetSettings(),
            presets = getAllPresets(),
            dailyMacros = getAllMacros(),
            macroEntries = macroEntryDao.getAll(),
            presetDisplaySettings = presetDisplaySettingsDao.getSettings()
        )
    }

    /**
     * Restore a backup into this device, filling gaps rather than mirroring.
     *
     * A day already on this phone is left alone. Overwriting its total with the
     * backup's would silently drop anything logged since the backup was taken, and
     * would leave the day's total disagreeing with the entries listed under it in
     * History. On a fresh install nothing is present to preserve, so everything lands.
     *
     * Presets are matched by name instead of the table being wiped and reinserted:
     * reinserting reassigns every preset id, which orphans preset_usages (the 7-day
     * sort) and the presetId on past AI jobs.
     *
     * Returns what it actually added.
     */
    suspend fun importBackup(backup: BackupData): BackupMerge.Plan {
        backup.targets?.let { saveTarget(it) }
        backup.widgetSettings?.let { saveWidgetSettings(it) }
        backup.presetDisplaySettings?.let { presetDisplaySettingsDao.upsert(it.copy(id = 1)) }

        val plan = BackupMerge.plan(snapshotForMerge(), snapshotOf(backup))
        applyMergePlan(plan)
        return plan
    }

    /**
     * Put back the per-entry log without duplicating what is already there.
     *
     * Entry ids are not stable across devices, so identity is the shape of the entry:
     * same day, same instant, same numbers. Re-running a restore is a no-op.
     */
    private suspend fun restoreEntries(entries: List<MacroEntry>) {
        if (entries.isEmpty()) return
        val seen = HashSet<String>()
        entries.groupBy { it.date }.forEach { (date, forDate) ->
            seen.clear()
            macroEntryDao.getEntriesForDate(date).forEach { seen += entrySignature(it) }
            forDate.forEach { entry ->
                if (seen.add(entrySignature(entry))) {
                    macroEntryDao.insert(entry.copy(id = 0))
                }
            }
        }
    }

    private fun entrySignature(e: MacroEntry): String =
        "${e.date}|${e.timestamp}|${e.proteinG}|${e.carbsG}|${e.fatG}"

    /** What this phone currently holds, for planning a merge against a backup. */
    suspend fun snapshotForMerge(): BackupMerge.Snapshot = BackupMerge.Snapshot(
        days = getAllMacros(),
        presets = getAllPresets(),
        entries = macroEntryDao.getAll()
    )

    fun snapshotOf(backup: BackupData): BackupMerge.Snapshot = BackupMerge.Snapshot(
        days = backup.dailyMacroList,
        presets = backup.presetList,
        entries = backup.entryList
    )

    /**
     * Apply only the additions in a plan. Days already on this phone are left exactly
     * as they are, including the ones the plan flagged as conflicting.
     */
    suspend fun applyMergePlan(plan: BackupMerge.Plan) {
        plan.daysToAdd.forEach { dailyMacroDao.upsert(it) }
        restoreEntries(plan.entriesToAdd)
        var order = presetDao.getMaxSortOrder()
        plan.presetsToAdd.forEach { preset ->
            presetDao.upsert(preset.copy(id = 0, sortOrder = ++order))
        }
    }

    /**
     * Check if local data is empty (for new app setup scenario)
     */
    suspend fun isLocalDataEmpty(): Boolean {
        return getAllMacros().isEmpty() && getAllPresets().isEmpty()
    }

    // ======================================================================
    // Preset ordering
    // ======================================================================

    fun getPresetDisplaySettingsFlow(): Flow<PresetDisplaySettings?> =
        presetDisplaySettingsDao.getSettingsFlow()

    suspend fun getPresetDisplaySettings(): PresetDisplaySettings =
        presetDisplaySettingsDao.getSettings() ?: PresetDisplaySettings()

    suspend fun savePresetDisplaySettings(settings: PresetDisplaySettings) =
        presetDisplaySettingsDao.upsert(settings)

    /**
     * Presets in the order the user asked for: their chosen sort, then optionally
     * split into AI and manual groups.
     */
    fun getSortedPresetsFlow(): Flow<List<MacroPreset>> {
        val windowStart = System.currentTimeMillis() - PresetUsage.WINDOW_MS
        return combine(
            presetDao.getAllFlow(),
            presetUsageDao.getCountsSinceFlow(windowStart),
            presetDisplaySettingsDao.getSettingsFlow()
        ) { presets, counts, settings ->
            val display = settings ?: PresetDisplaySettings()
            val uses = counts.associate { it.presetId to it.uses }
            sortPresets(presets, uses, display)
        }
    }

    suspend fun getSortedPresets(): List<MacroPreset> {
        val windowStart = System.currentTimeMillis() - PresetUsage.WINDOW_MS
        val uses = presetUsageDao.getCountsSince(windowStart).associate { it.presetId to it.uses }
        return sortPresets(presetDao.getAll(), uses, getPresetDisplaySettings())
    }

    private fun sortPresets(
        presets: List<MacroPreset>,
        uses: Map<Long, Int>,
        settings: PresetDisplaySettings
    ): List<MacroPreset> {
        val ordered = when (settings.sortMode) {
            PresetSortMode.ALPHABETICAL ->
                presets.sortedBy { it.name.lowercase() }
            PresetSortMode.MANUAL ->
                presets.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
            PresetSortMode.MOST_USED_WEEK ->
                // Unused presets keep alphabetical order rather than shuffling around.
                presets.sortedWith(
                    compareByDescending<MacroPreset> { uses[it.id] ?: 0 }
                        .thenByDescending { it.lastUsedAt }
                        .thenBy { it.name.lowercase() }
                )
            PresetSortMode.RECENTLY_USED ->
                presets.sortedWith(
                    compareByDescending<MacroPreset> { it.lastUsedAt }
                        .thenBy { it.name.lowercase() }
                )
        }
        if (!settings.splitAiAndManual) return ordered
        val (ai, manual) = ordered.partition { it.isAi }
        return if (settings.aiOnTop) ai + manual else manual + ai
    }

    suspend fun getUntaggedPresets(): List<MacroPreset> = presetDao.getUntagged()

    suspend fun saveSearchTags(presetId: Long, tags: List<String>) {
        presetDao.updateSearchTags(presetId, tags.joinToString(", "))
    }

    /** Persists a drag-and-drop reorder. */
    suspend fun savePresetOrder(presetIds: List<Long>) {
        presetIds.forEachIndexed { index, id -> presetDao.updateSortOrder(id, index) }
    }

    // ======================================================================
    // AI estimator
    // ======================================================================

    fun getAiSettingsFlow(): Flow<AiSettings?> = aiSettingsDao.getSettingsFlow()

    suspend fun getAiSettings(): AiSettings = aiSettingsDao.getSettings() ?: AiSettings()

    suspend fun saveAiSettings(settings: AiSettings) = aiSettingsDao.upsert(settings)

    fun getAiJobsFlow(): Flow<List<AiJob>> = aiJobDao.getAllFlow()

    fun getWatchedAiJobsFlow(): Flow<List<AiJob>> =
        aiJobDao.getByStatusesFlow(AiJob.WATCHED_STATUSES)

    suspend fun getAiJob(clientJobId: String): AiJob? = aiJobDao.getByClientId(clientJobId)

    suspend fun getAiJobByServerId(serverJobId: String): AiJob? =
        aiJobDao.getByServerId(serverJobId)

    suspend fun getWatchedAiJobs(): List<AiJob> = aiJobDao.getByStatuses(AiJob.WATCHED_STATUSES)

    suspend fun saveAiJob(job: AiJob) = aiJobDao.upsert(job.copy(updatedAt = System.currentTimeMillis()))

    suspend fun getLatestAiServerUpdate(): Long = aiJobDao.getLatestServerUpdate()

    fun parseAiResult(job: AiJob): AiResult? =
        job.resultJson?.let {
            runCatching { gson.fromJson(it, AiResult::class.java) }.getOrNull()
        }

    /**
     * Logs an estimate against the day, and creates the preset that goes with it.
     *
     * Returns the updated job. Safe to call twice: a job that has already been
     * applied is revised instead, so a duplicated notification can't double-count.
     */
    suspend fun applyAiResult(job: AiJob, result: AiResult): AiJob {
        if (job.appliedEntryId != null) {
            return reviseAiEntry(job, result)
        }

        val today = getTodayDate()
        val total = result.total
        val presetName = result.preset_name.ifBlank { "AI entry" }

        val entryId = macroEntryDao.insert(
            MacroEntry(
                date = today,
                proteinG = total.protein_g,
                carbsG = total.carbs_g,
                fatG = total.fat_g,
                source = MacroEntry.SOURCE_AI,
                aiJobId = job.clientJobId,
                note = presetName
            )
        )
        addToDailyTotals(today, total.protein_g, total.carbs_g, total.fat_g)

        val presetId = upsertAiPreset(job.presetId, presetName, total.protein_g, total.carbs_g, total.fat_g)

        val updated = job.copy(
            appliedProteinG = total.protein_g,
            appliedCarbsG = total.carbs_g,
            appliedFatG = total.fat_g,
            appliedEntryId = entryId,
            presetId = presetId,
            updatedAt = System.currentTimeMillis()
        )
        aiJobDao.upsert(updated)
        return updated
    }

    /**
     * Applies the difference after a follow-up answer refines the estimate.
     * Only the delta touches the day's totals, so the entry's own date stays correct
     * even when the answer arrives the next morning.
     */
    suspend fun reviseAiEntry(job: AiJob, result: AiResult): AiJob {
        val entryId = job.appliedEntryId ?: return applyAiResult(job, result)
        val entry = macroEntryDao.getById(entryId) ?: return applyAiResult(job.copy(appliedEntryId = null), result)

        val total = result.total
        val presetName = result.preset_name.ifBlank { entry.note ?: "AI entry" }

        macroEntryDao.updateMacros(entryId, total.protein_g, total.carbs_g, total.fat_g)

        // A job excluded from totals contributes nothing, so there is no delta to apply.
        if (!job.excludedFromTotals) {
            addToDailyTotals(
                entry.date,
                total.protein_g - job.appliedProteinG,
                total.carbs_g - job.appliedCarbsG,
                total.fat_g - job.appliedFatG
            )
        }

        val presetId = upsertAiPreset(job.presetId, presetName, total.protein_g, total.carbs_g, total.fat_g)

        val updated = job.copy(
            appliedProteinG = total.protein_g,
            appliedCarbsG = total.carbs_g,
            appliedFatG = total.fat_g,
            presetId = presetId,
            updatedAt = System.currentTimeMillis()
        )
        aiJobDao.upsert(updated)
        return updated
    }

    /**
     * Soft delete: keeps the entry in History for context but takes its macros back
     * out of the running totals (or puts them back).
     */
    suspend fun setAiJobExcluded(job: AiJob, excluded: Boolean): AiJob {
        if (job.excludedFromTotals == excluded) return job
        val entryId = job.appliedEntryId
        if (entryId != null) {
            val entry = macroEntryDao.getById(entryId)
            if (entry != null) {
                macroEntryDao.setHidden(entryId, excluded)
                val sign = if (excluded) -1 else 1
                addToDailyTotals(
                    entry.date,
                    sign * entry.proteinG,
                    sign * entry.carbsG,
                    sign * entry.fatG
                )
            }
        }
        val updated = job.copy(excludedFromTotals = excluded, updatedAt = System.currentTimeMillis())
        aiJobDao.upsert(updated)
        return updated
    }

    /** Removes the job, its entry, and its contribution to the day's totals. */
    suspend fun deleteAiJob(job: AiJob) {
        val entryId = job.appliedEntryId
        if (entryId != null) {
            val entry = macroEntryDao.getById(entryId)
            if (entry != null) {
                if (!entry.hidden) {
                    addToDailyTotals(entry.date, -entry.proteinG, -entry.carbsG, -entry.fatG)
                }
                macroEntryDao.deleteById(entryId)
            }
        }
        aiJobDao.deleteByClientId(job.clientJobId)
    }

    /** Toggles an individual entry's contribution to its day, AI-created or not. */
    suspend fun setEntryHidden(entryId: Long, hidden: Boolean) {
        val entry = macroEntryDao.getById(entryId) ?: return
        if (entry.hidden == hidden) return
        macroEntryDao.setHidden(entryId, hidden)
        val sign = if (hidden) -1 else 1
        addToDailyTotals(entry.date, sign * entry.proteinG, sign * entry.carbsG, sign * entry.fatG)
    }

    private suspend fun upsertAiPreset(
        existingId: Long?,
        name: String,
        protein: Int,
        carbs: Int,
        fat: Int
    ): Long {
        val existing = existingId?.let { presetDao.getById(it) }
        val preset = MacroPreset(
            id = existing?.id ?: 0,
            name = name,
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            source = MacroPreset.SOURCE_AI,
            sortOrder = existing?.sortOrder ?: (presetDao.getMaxSortOrder() + 1),
            lastUsedAt = existing?.lastUsedAt ?: System.currentTimeMillis()
        )
        val newId = presetDao.upsert(preset)
        // upsert returns -1 when REPLACE updated in place rather than inserting.
        return if (newId > 0) newId else preset.id
    }

    /**
     * Adds a signed delta to a day's totals, creating the row if needed. Negative
     * deltas are clamped at zero per macro, matching [addMacros].
     */
    private suspend fun addToDailyTotals(date: String, protein: Int, carbs: Int, fat: Int) {
        if (protein == 0 && carbs == 0 && fat == 0) return
        val existing = dailyMacroDao.getByDate(date)
        if (existing == null) {
            dailyMacroDao.upsert(
                DailyMacro(
                    date = date,
                    proteinG = maxOf(0, protein),
                    carbsG = maxOf(0, carbs),
                    fatG = maxOf(0, fat)
                )
            )
        } else {
            dailyMacroDao.addMacros(date, protein, carbs, fat)
        }
    }

    // ======================================================================
    // Planning threads
    // ======================================================================

    fun getThreadsFlow(): Flow<List<AiThread>> = aiThreadDao.getAllFlow()

    fun getThreadFlow(clientThreadId: String): Flow<AiThread?> =
        aiThreadDao.getByClientIdFlow(clientThreadId)

    fun getThreadMessagesFlow(clientThreadId: String): Flow<List<AiThreadMessage>> =
        aiThreadMessageDao.getForThreadFlow(clientThreadId)

    suspend fun getThread(clientThreadId: String): AiThread? =
        aiThreadDao.getByClientId(clientThreadId)

    suspend fun getThreadMessages(clientThreadId: String): List<AiThreadMessage> =
        aiThreadMessageDao.getForThread(clientThreadId)

    suspend fun getThreadByServerId(serverThreadId: String): AiThread? =
        aiThreadDao.getByServerId(serverThreadId)

    suspend fun getBusyThreads(): List<AiThread> = aiThreadDao.getBusy()

    suspend fun getLatestThreadServerUpdate(): Long = aiThreadDao.getLatestServerUpdate()

    suspend fun saveThread(thread: AiThread) =
        aiThreadDao.upsert(thread.copy(updatedAt = System.currentTimeMillis()))

    suspend fun replaceThreadMessages(clientThreadId: String, messages: List<AiThreadMessage>) {
        // Applied-proposal flags are local state the server knows nothing about, so
        // carry them across the wholesale replace.
        val applied = aiThreadMessageDao.getForThread(clientThreadId)
            .associate { it.id to it.appliedIndicesJson }
        aiThreadMessageDao.deleteForThread(clientThreadId)
        aiThreadMessageDao.upsertAll(
            messages.map { message ->
                applied[message.id]?.let { message.copy(appliedIndicesJson = it) } ?: message
            }
        )
    }

    suspend fun deleteThread(clientThreadId: String) {
        aiThreadMessageDao.deleteForThread(clientThreadId)
        aiThreadDao.deleteByClientId(clientThreadId)
    }

    suspend fun getThreadMessage(id: String): AiThreadMessage? = aiThreadMessageDao.getById(id)

    suspend fun markProposalApplied(messageId: String, index: Int) {
        val message = aiThreadMessageDao.getById(messageId) ?: return
        val applied = parseAppliedIndices(message).toMutableSet()
        applied.add(index)
        aiThreadMessageDao.setAppliedIndices(messageId, gson.toJson(applied.sorted()))
    }

    fun parseAppliedIndices(message: AiThreadMessage): Set<Int> = try {
        val list: List<Double> =
            gson.fromJson(message.appliedIndicesJson, object : TypeToken<List<Double>>() {}.type)
                ?: emptyList()
        list.map { it.toInt() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun parseProposals(message: AiThreadMessage): List<AiProposal> = try {
        gson.fromJson(message.proposalsJson, object : TypeToken<List<AiProposal>>() {}.type)
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Logs a proposal against today, optionally saving it as a preset.
     * Same path as a manual add, so Undo and History treat it identically.
     */
    suspend fun applyProposal(proposal: AiProposal, savePreset: Boolean): Long {
        val today = getTodayDate()
        val entryId = macroEntryDao.insert(
            MacroEntry(
                date = today,
                proteinG = maxOf(0, proposal.protein_g),
                carbsG = maxOf(0, proposal.carbs_g),
                fatG = maxOf(0, proposal.fat_g),
                source = MacroEntry.SOURCE_AI,
                note = proposal.name
            )
        )
        addToDailyTotals(today, proposal.protein_g, proposal.carbs_g, proposal.fat_g)

        if (savePreset) {
            val existing = presetDao.getByName(proposal.name)
            val preset = MacroPreset(
                id = existing?.id ?: 0,
                name = proposal.name,
                proteinG = maxOf(0, proposal.protein_g),
                carbsG = maxOf(0, proposal.carbs_g),
                fatG = maxOf(0, proposal.fat_g),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                source = MacroPreset.SOURCE_AI,
                sortOrder = existing?.sortOrder ?: (presetDao.getMaxSortOrder() + 1),
                lastUsedAt = System.currentTimeMillis()
            )
            presetDao.upsert(preset)
        }
        return entryId
    }

    /**
     * The snapshot the planning assistant reasons over: targets, where today stands,
     * what the user actually eats, and the last week for context.
     */
    suspend fun buildPlanningContext(): String {
        val target = getTarget()
        val settings = getWidgetSettings()
        val today = getTodayDate()
        val todayMacros = dailyMacroDao.getByDate(today) ?: DailyMacro(date = today)
        val entries = macroEntryDao.getEntriesForDate(today).filter { !it.hidden }
        val windowStart = System.currentTimeMillis() - PresetUsage.WINDOW_MS
        val uses = presetUsageDao.getCountsSince(windowStart).associate { it.presetId to it.uses }
        val presets = presetDao.getAll()
        val recent = dailyMacroDao.getAll()
            .filter { it.date < today }
            .sortedByDescending { it.date }
            .take(7)

        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

        val context = mapOf(
            "date" to today,
            "now" to timeFormat.format(java.util.Date()),
            "day_reset_hour" to settings.dayResetHour,
            "targets" to mapOf(
                "protein_g" to target.proteinG,
                "carbs_g" to target.carbsG,
                "fat_g" to target.fatG,
                "calories" to target.caloriesTarget
            ),
            "today" to mapOf(
                "protein_g" to todayMacros.proteinG,
                "carbs_g" to todayMacros.carbsG,
                "fat_g" to todayMacros.fatG,
                "calories" to todayMacros.calories,
                "remaining" to mapOf(
                    "protein_g" to target.proteinG - todayMacros.proteinG,
                    "carbs_g" to target.carbsG - todayMacros.carbsG,
                    "fat_g" to target.fatG - todayMacros.fatG,
                    "calories" to target.caloriesTarget - todayMacros.calories
                ),
                "annotation" to todayMacros.annotation,
                "entries" to entries.map { entry ->
                    mapOf(
                        "time" to timeFormat.format(java.util.Date(entry.timestamp)),
                        "protein_g" to entry.proteinG,
                        "carbs_g" to entry.carbsG,
                        "fat_g" to entry.fatG,
                        "calories" to entry.calories,
                        "source" to entry.source,
                        "note" to entry.note
                    )
                }
            ),
            "presets" to presets.map { preset ->
                mapOf(
                    "name" to preset.name,
                    "protein_g" to preset.proteinG,
                    "carbs_g" to preset.carbsG,
                    "fat_g" to preset.fatG,
                    "calories" to preset.calories,
                    "uses_7d" to (uses[preset.id] ?: 0),
                    "created_by" to preset.source
                )
            },
            "recent_days" to recent.map { day ->
                mapOf(
                    "date" to day.date,
                    "protein_g" to day.proteinG,
                    "carbs_g" to day.carbsG,
                    "fat_g" to day.fatG,
                    "calories" to day.calories,
                    "annotation" to day.annotation
                )
            }
        )
        return gson.toJson(context)
    }
}
