package com.macropad.app

import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntry
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.sync.BackupMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeTest {

    private fun day(date: String, p: Int = 10, c: Int = 20, f: Int = 5) =
        DailyMacro(date = date, proteinG = p, carbsG = c, fatG = f)

    private fun entry(date: String, ts: Long, p: Int = 10) =
        MacroEntry(date = date, timestamp = ts, proteinG = p)

    private fun preset(name: String) = MacroPreset(name = name, proteinG = 1)

    @Test
    fun `days only in the backup are added`() {
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(days = listOf(day("2026-01-02"))),
            remote = BackupMerge.Snapshot(days = listOf(day("2026-01-01"), day("2026-01-02")))
        )
        assertEquals(listOf("2026-01-01"), plan.daysToAdd.map { it.date })
    }

    @Test
    fun `a day already on the phone is never overwritten`() {
        val local = day("2026-01-01", p = 99)
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(days = listOf(local)),
            remote = BackupMerge.Snapshot(days = listOf(day("2026-01-01", p = 11)))
        )
        assertTrue(plan.daysToAdd.isEmpty())
        assertEquals(1, plan.conflicts.size)
        assertEquals(99, plan.conflicts.first().local.proteinG)
    }

    @Test
    fun `identical days are not reported as conflicts`() {
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(days = listOf(day("2026-01-01"))),
            remote = BackupMerge.Snapshot(days = listOf(day("2026-01-01")))
        )
        assertTrue(plan.conflicts.isEmpty())
        assertFalse(plan.changesAnything)
    }

    @Test
    fun `entries come across only for days being added`() {
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(days = listOf(day("2026-01-02"))),
            remote = BackupMerge.Snapshot(
                days = listOf(day("2026-01-01"), day("2026-01-02")),
                entries = listOf(entry("2026-01-01", 100), entry("2026-01-02", 200))
            )
        )
        // Adding an entry to a day whose totals we are deliberately not touching
        // would double-count it.
        assertEquals(listOf(100L), plan.entriesToAdd.map { it.timestamp })
    }

    @Test
    fun `presets match by name case and space insensitively`() {
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(presets = listOf(preset("Goldfish"))),
            remote = BackupMerge.Snapshot(presets = listOf(preset(" goldfish "), preset("Yogurt")))
        )
        assertEquals(listOf("Yogurt"), plan.presetsToAdd.map { it.name })
    }

    @Test
    fun `duplicate names inside the backup are added once`() {
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(),
            remote = BackupMerge.Snapshot(presets = listOf(preset("Yogurt"), preset("yogurt")))
        )
        assertEquals(1, plan.presetsToAdd.size)
    }

    @Test
    fun `local-only days are counted and kept`() {
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(days = listOf(day("2026-01-05"), day("2026-01-06"))),
            remote = BackupMerge.Snapshot(days = listOf(day("2026-01-05")))
        )
        assertEquals(1, plan.localOnlyDays)
        assertTrue(plan.daysToAdd.isEmpty())
    }

    @Test
    fun `an empty backup changes nothing`() {
        val local = BackupMerge.Snapshot(days = listOf(day("2026-01-01")), presets = listOf(preset("A")))
        val plan = BackupMerge.plan(local = local, remote = BackupMerge.Snapshot())
        assertFalse(plan.changesAnything)
        assertTrue(BackupMerge.describe(plan, BackupMerge.Snapshot()).contains("empty"))
    }

    @Test
    fun `a fresh phone takes everything`() {
        val remote = BackupMerge.Snapshot(
            days = listOf(day("2026-01-01"), day("2026-01-02")),
            presets = listOf(preset("A")),
            entries = listOf(entry("2026-01-01", 1), entry("2026-01-02", 2))
        )
        val plan = BackupMerge.plan(local = BackupMerge.Snapshot(), remote = remote)
        assertEquals(2, plan.daysToAdd.size)
        assertEquals(2, plan.entriesToAdd.size)
        assertEquals(1, plan.presetsToAdd.size)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `days to add are ordered so the description reads as a range`() {
        val plan = BackupMerge.plan(
            local = BackupMerge.Snapshot(),
            remote = BackupMerge.Snapshot(
                days = listOf(day("2026-03-09"), day("2026-01-01"), day("2026-02-02"))
            )
        )
        assertEquals("2026-01-01", plan.daysToAdd.first().date)
        assertEquals("2026-03-09", plan.daysToAdd.last().date)
        val text = BackupMerge.describe(plan, BackupMerge.Snapshot(days = plan.daysToAdd))
        assertTrue(text.contains("2026-01-01 to 2026-03-09"))
    }

    @Test
    fun `planning is idempotent - re-running after a merge adds nothing`() {
        val remote = BackupMerge.Snapshot(
            days = listOf(day("2026-01-01")),
            presets = listOf(preset("A")),
            entries = listOf(entry("2026-01-01", 1))
        )
        val first = BackupMerge.plan(BackupMerge.Snapshot(), remote)
        val merged = BackupMerge.Snapshot(
            days = first.daysToAdd,
            presets = first.presetsToAdd,
            entries = first.entriesToAdd
        )
        val second = BackupMerge.plan(merged, remote)
        assertFalse(second.changesAnything)
    }
}
