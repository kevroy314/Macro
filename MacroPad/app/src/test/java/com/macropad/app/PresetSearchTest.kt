package com.macropad.app

import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.ui.screens.PresetSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search behaviour, using keywords of the shape the daemon actually returns.
 */
class PresetSearchTest {

    private fun preset(name: String, tags: String) =
        MacroPreset(id = name.hashCode().toLong(), name = name, searchTags = tags)

    private val presets = listOf(
        preset("Chicken breast", "high protein, low carb, low calorie, lean, poultry, meal"),
        preset("Goldfish", "snack, cracker, crunchy, salty, carb heavy, cheese"),
        preset("Greek yogurt", "high protein, low carb, low calorie, creamy, breakfast, dairy"),
        preset("Zucchini", "vegetable, low calorie, low carb, side, light"),
        preset("Almonds", "snack, nut, crunchy, high fat, healthy fat, portable"),
        preset("Chipotle chicken bowl + guac", "burrito bowl, mexican, restaurant, filling, meal")
    )

    private fun search(query: String) = PresetSearch.filter(presets, query).map { it.name }

    @Test
    fun `finds by meaning, not just spelling`() {
        assertEquals(listOf("Goldfish"), search("salty snack"))
        assertEquals(setOf("Goldfish", "Almonds"), search("snack").toSet())
        assertEquals(listOf("Greek yogurt"), search("breakfast"))
        assertEquals(listOf("Chipotle chicken bowl + guac"), search("mexican"))
    }

    @Test
    fun `matches names too, and ranks them above tag matches`() {
        val chicken = search("chicken")
        assertEquals("Chicken breast", chicken.first())
        assertTrue("Chipotle chicken bowl + guac" in chicken)
    }

    @Test
    fun `every term has to match something`() {
        // "crunchy" hits both snacks, "nut" only almonds.
        assertEquals(listOf("Almonds"), search("crunchy nut"))
        assertEquals(emptyList<String>(), search("crunchy mexican"))
    }

    @Test
    fun `prefixes match so results narrow while typing`() {
        assertEquals(listOf("Goldfish"), search("gold"))
        assertEquals(listOf("Zucchini"), search("zucc"))
    }

    @Test
    fun `no query returns everything untouched`() {
        assertEquals(presets.map { it.name }, PresetSearch.filter(presets, "  ").map { it.name })
    }

    @Test
    fun `nonsense matches nothing`() {
        assertEquals(emptyList<String>(), search("xyzzy"))
    }

    @Test
    fun `untagged presets are still findable by name`() {
        val untagged = listOf(preset("Banana", ""))
        assertEquals(listOf("Banana"), PresetSearch.filter(untagged, "banana").map { it.name })
        assertEquals(emptyList<String>(), PresetSearch.filter(untagged, "fruit").map { it.name })
    }
}
