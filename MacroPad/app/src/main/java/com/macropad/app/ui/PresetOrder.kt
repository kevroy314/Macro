package com.macropad.app.ui

import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.PresetDisplaySettings
import com.macropad.app.data.entity.PresetSortMode

/**
 * The order presets appear in, everywhere.
 *
 * The app's two lists and the home-screen widget all read the same sorted flow, so
 * this is the single answer to "why is that one first". Pure, so the rules can be
 * tested — the ordering is easy to get subtly wrong and impossible to notice until
 * someone's most-used preset is in the wrong place.
 */
object PresetOrder {

    fun sort(
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

        val grouped = if (!settings.splitAiAndManual) {
            ordered
        } else {
            val (ai, manual) = ordered.partition { it.isAi }
            if (settings.aiOnTop) ai + manual else manual + ai
        }

        // Pins float to the top, keeping their order relative to each other. Applied
        // last so it outranks both the sort mode and the AI/manual split — the whole
        // point of a pin is that it does not move when you change your mind about
        // sorting.
        val (pinned, rest) = grouped.partition { it.pinned }
        return pinned + rest
    }
}
