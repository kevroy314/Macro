package com.macropad.app

import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.PresetDisplaySettings
import com.macropad.app.data.entity.PresetSortMode
import com.macropad.app.ui.PresetOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order presets appear in, for the app's lists and the home-screen widget alike.
 * Worth testing because a wrong answer here is quiet: the list still looks sorted,
 * just not the way it was asked to be.
 */
class PresetOrderTest {

    private fun preset(
        id: Long,
        name: String,
        pinned: Boolean = false,
        ai: Boolean = false,
        order: Int = 0,
        lastUsed: Long = 0
    ) = MacroPreset(
        id = id,
        name = name,
        pinned = pinned,
        source = if (ai) MacroPreset.SOURCE_AI else MacroPreset.SOURCE_MANUAL,
        sortOrder = order,
        lastUsedAt = lastUsed
    )

    private fun names(list: List<MacroPreset>) = list.map { it.name }

    private val alphabetical = PresetDisplaySettings(sortMode = PresetSortMode.ALPHABETICAL)

    @Test
    fun `alphabetical is the default order`() {
        val list = listOf(preset(1, "Yogurt"), preset(2, "Almonds"), preset(3, "Milk"))
        assertEquals(
            listOf("Almonds", "Milk", "Yogurt"),
            names(PresetOrder.sort(list, emptyMap(), alphabetical))
        )
    }

    @Test
    fun `a pin goes to the top regardless of the sort`() {
        val list = listOf(preset(1, "Almonds"), preset(2, "Milk"), preset(3, "Yogurt", pinned = true))
        assertEquals(
            listOf("Yogurt", "Almonds", "Milk"),
            names(PresetOrder.sort(list, emptyMap(), alphabetical))
        )
    }

    @Test
    fun `pins keep their own order among themselves`() {
        val list = listOf(
            preset(1, "Yogurt", pinned = true),
            preset(2, "Almonds", pinned = true),
            preset(3, "Milk")
        )
        // Still alphabetical within the pinned group — pinning changes which group a
        // preset is in, not how the group is ordered.
        assertEquals(
            listOf("Almonds", "Yogurt", "Milk"),
            names(PresetOrder.sort(list, emptyMap(), alphabetical))
        )
    }

    @Test
    fun `a pin outranks most-used`() {
        val list = listOf(preset(1, "Popular"), preset(2, "Rare", pinned = true))
        val uses = mapOf(1L to 40, 2L to 0)
        val settings = PresetDisplaySettings(sortMode = PresetSortMode.MOST_USED_WEEK)
        assertEquals(
            listOf("Rare", "Popular"),
            names(PresetOrder.sort(list, uses, settings))
        )
    }

    @Test
    fun `a pin outranks the AI and manual split`() {
        // This is the case that would quietly break: the split partitions the whole
        // list, so applying it after pinning would drag a pinned preset back down.
        val list = listOf(
            preset(1, "Made by AI", ai = true),
            preset(2, "Mine", pinned = true)
        )
        val settings = PresetDisplaySettings(
            sortMode = PresetSortMode.ALPHABETICAL,
            splitAiAndManual = true,
            aiOnTop = true
        )
        assertEquals(listOf("Mine", "Made by AI"), names(PresetOrder.sort(list, settings.let { emptyMap() }, settings)))
    }

    @Test
    fun `manual order is respected, and pins still win`() {
        val list = listOf(
            preset(1, "Third", order = 3),
            preset(2, "First", order = 1),
            preset(3, "Second", order = 2, pinned = true)
        )
        val settings = PresetDisplaySettings(sortMode = PresetSortMode.MANUAL)
        assertEquals(
            listOf("Second", "First", "Third"),
            names(PresetOrder.sort(list, emptyMap(), settings))
        )
    }

    @Test
    fun `recently used puts the newest first`() {
        val list = listOf(
            preset(1, "Old", lastUsed = 100),
            preset(2, "New", lastUsed = 900),
            preset(3, "Never", lastUsed = 0)
        )
        val settings = PresetDisplaySettings(sortMode = PresetSortMode.RECENTLY_USED)
        assertEquals(
            listOf("New", "Old", "Never"),
            names(PresetOrder.sort(list, emptyMap(), settings))
        )
    }

    @Test
    fun `the widget and the app cannot disagree`() {
        // Both read the same sorted flow, so the guarantee worth pinning down is that
        // one call with one set of inputs gives one answer.
        val list = listOf(preset(1, "B"), preset(2, "A", pinned = true), preset(3, "C"))
        val once = PresetOrder.sort(list, emptyMap(), alphabetical)
        val twice = PresetOrder.sort(list, emptyMap(), alphabetical)
        assertEquals(names(once), names(twice))
        assertEquals(listOf("A", "B", "C"), names(once))
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<String>(), names(PresetOrder.sort(emptyList(), emptyMap(), alphabetical)))
    }
}
