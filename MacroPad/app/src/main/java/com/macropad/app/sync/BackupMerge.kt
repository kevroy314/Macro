package com.macropad.app.sync

import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntry
import com.macropad.app.data.entity.MacroPreset

/**
 * Working out what a backup would add to what's already on the device.
 *
 * Deliberately conservative and non-destructive: a day that exists locally is never
 * overwritten, only reported. Migration should be able to fail halfway and leave the
 * phone exactly as it was.
 *
 * Pure by design so it can be tested — this is the code path where a mistake costs
 * someone their history.
 */
object BackupMerge {

    data class Snapshot(
        val days: List<DailyMacro> = emptyList(),
        val presets: List<MacroPreset> = emptyList(),
        val entries: List<MacroEntry> = emptyList()
    )

    /** A date both sides know about, where the totals disagree. */
    data class DayConflict(val date: String, val local: DailyMacro, val remote: DailyMacro)

    data class Plan(
        val daysToAdd: List<DailyMacro> = emptyList(),
        val entriesToAdd: List<MacroEntry> = emptyList(),
        val presetsToAdd: List<MacroPreset> = emptyList(),
        val conflicts: List<DayConflict> = emptyList(),
        val localOnlyDays: Int = 0
    ) {
        val isEmpty: Boolean
            get() = daysToAdd.isEmpty() && presetsToAdd.isEmpty()

        val changesAnything: Boolean
            get() = !isEmpty
    }

    fun plan(local: Snapshot, remote: Snapshot): Plan {
        val localByDate = local.days.associateBy { it.date }
        val remoteByDate = remote.days.associateBy { it.date }

        val daysToAdd = remote.days
            .filter { it.date !in localByDate }
            .sortedBy { it.date }

        val addedDates = daysToAdd.map { it.date }.toSet()

        // Only bring entries across for days being added. Adding them to a day that
        // already exists locally would double-count against totals we aren't touching.
        val entriesToAdd = remote.entries
            .filter { it.date in addedDates }
            .sortedBy { it.timestamp }

        val localNames = local.presets.map { it.name.trim().lowercase() }.toSet()
        val presetsToAdd = remote.presets
            .filter { it.name.trim().lowercase() !in localNames }
            .distinctBy { it.name.trim().lowercase() }

        val conflicts = remote.days.mapNotNull { remoteDay ->
            val localDay = localByDate[remoteDay.date] ?: return@mapNotNull null
            val same = localDay.proteinG == remoteDay.proteinG &&
                localDay.carbsG == remoteDay.carbsG &&
                localDay.fatG == remoteDay.fatG
            if (same) null else DayConflict(remoteDay.date, localDay, remoteDay)
        }.sortedBy { it.date }

        return Plan(
            daysToAdd = daysToAdd,
            entriesToAdd = entriesToAdd,
            presetsToAdd = presetsToAdd,
            conflicts = conflicts,
            localOnlyDays = local.days.count { it.date !in remoteByDate }
        )
    }

    /** What the plan will do, in the words the user needs to decide. */
    fun describe(plan: Plan, remote: Snapshot): String {
        if (remote.days.isEmpty() && remote.presets.isEmpty()) {
            return "The Dropbox backup is empty — there's nothing to bring across."
        }
        val parts = mutableListOf<String>()

        parts += if (plan.daysToAdd.isEmpty()) {
            "Every day in Dropbox (${remote.days.size}) is already on this phone."
        } else {
            val first = plan.daysToAdd.first().date
            val last = plan.daysToAdd.last().date
            val range = if (first == last) first else "$first to $last"
            "${plan.daysToAdd.size} day(s) are in Dropbox but not on this phone ($range)."
        }

        if (plan.entriesToAdd.isNotEmpty()) {
            parts += "${plan.entriesToAdd.size} individual entries come with them."
        }
        if (plan.presetsToAdd.isNotEmpty()) {
            parts += "${plan.presetsToAdd.size} preset(s) will be added."
        }
        if (plan.localOnlyDays > 0) {
            parts += "${plan.localOnlyDays} day(s) exist only on this phone and are kept."
        }
        if (plan.conflicts.isNotEmpty()) {
            parts += "${plan.conflicts.size} day(s) differ between the two; " +
                "this phone's numbers are kept."
        }
        return parts.joinToString(" ")
    }
}
