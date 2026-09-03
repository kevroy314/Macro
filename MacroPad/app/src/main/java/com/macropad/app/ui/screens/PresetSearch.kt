package com.macropad.app.ui.screens

import com.macropad.app.data.entity.MacroPreset

/**
 * Matching a typed query against saved presets.
 *
 * Every term has to match somewhere, but it can match either the name or the
 * keywords the daemon generated for the preset — which is what lets "salty snack"
 * find Goldfish. Purely local, so it runs on every keystroke.
 */
object PresetSearch {

    fun filter(presets: List<MacroPreset>, query: String): List<MacroPreset> {
        val terms = query.trim().lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (terms.isEmpty()) return presets

        return presets
            .mapNotNull { preset ->
                val score = score(preset, terms)
                if (score > 0) preset to score else null
            }
            // Stable within a score band: the incoming order is the user's chosen sort.
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** 0 means "no match"; higher is a better match. */
    private fun score(preset: MacroPreset, terms: List<String>): Int {
        val name = preset.name.lowercase()
        val tags = preset.searchTags.lowercase()
        var total = 0

        for (term in terms) {
            val termScore = when {
                name == term -> 100
                name.startsWith(term) -> 60
                name.split(' ').any { it.startsWith(term) } -> 45
                name.contains(term) -> 30
                tags.split(',').any { it.trim() == term } -> 20
                tags.contains(term) -> 12
                else -> 0
            }
            // Every term must land somewhere, or this isn't the preset they meant.
            if (termScore == 0) return 0
            total += termScore
        }
        return total
    }
}
