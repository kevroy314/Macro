package com.macropad.app

import androidx.compose.ui.graphics.Color
import com.macropad.app.ui.theme.WidgetPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast rule, which decides whether anyone can read their widget.
 *
 * Worth testing rather than eyeballing: it only goes wrong on somebody else's
 * wallpaper, in a combination the author never has on screen.
 */
class WidgetPaletteTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
    private val TRANSPARENT = 0x00000000

    private fun isLight(c: Color) = c.red + c.green + c.blue > 1.5f

    @Test
    fun `dark background gets light text`() {
        assertTrue(isLight(WidgetPalette.autoTextColor(BLACK, systemDark = true)))
        assertTrue(isLight(WidgetPalette.autoTextColor(BLACK, systemDark = false)))
    }

    @Test
    fun `light background gets dark text`() {
        assertTrue(!isLight(WidgetPalette.autoTextColor(WHITE, systemDark = true)))
        assertTrue(!isLight(WidgetPalette.autoTextColor(WHITE, systemDark = false)))
    }

    @Test
    fun `an opaque background wins over the system theme`() {
        // The case that matters: a white widget in dark mode must not get white text.
        val text = WidgetPalette.autoTextColor(WHITE, systemDark = true)
        assertTrue("white background in dark mode must use dark text", !isLight(text))
    }

    @Test
    fun `a transparent background falls back to the system theme`() {
        // Nothing to measure — whatever is behind it is the wallpaper.
        assertTrue(isLight(WidgetPalette.autoTextColor(TRANSPARENT, systemDark = true)))
        assertTrue(!isLight(WidgetPalette.autoTextColor(TRANSPARENT, systemDark = false)))
    }

    @Test
    fun `a barely-there tint still counts as transparent`() {
        // 10% white does not cover a dark wallpaper, so it must not drive the decision.
        val faintWhite = 0x1AFFFFFF
        assertTrue(isLight(WidgetPalette.autoTextColor(faintWhite, systemDark = true)))
    }

    @Test
    fun `luminance follows perception, not the average channel`() {
        // Pure green is far brighter to an eye than pure blue, though both are one
        // full channel. Averaging would call them identical and pick the wrong text.
        val green = WidgetPalette.relativeLuminance(0xFF00FF00.toInt())
        val blue = WidgetPalette.relativeLuminance(0xFF0000FF.toInt())
        assertTrue("green ($green) should read far lighter than blue ($blue)", green > blue * 5)
        assertEquals(WidgetPalette.relativeLuminance(WHITE), 1.0f, 0.01f)
        assertEquals(WidgetPalette.relativeLuminance(BLACK), 0.0f, 0.01f)
    }

    @Test
    fun `mid greens are treated as light`() {
        // A saturated green background is bright enough to need dark text, which the
        // naive "is the red channel high" check gets wrong.
        assertTrue(!isLight(WidgetPalette.autoTextColor(0xFF4ADE80.toInt(), systemDark = true)))
    }

    @Test
    fun `an explicit text colour overrides the rule entirely`() {
        val hotPink = 0xFFFF69B4.toInt()
        val palette = WidgetPalette.of(backgroundArgb = WHITE, textArgb = hotPink)
        assertEquals(Color(hotPink), palette.text)
    }

    @Test
    fun `auto is the default and is not mistaken for a colour`() {
        val onDark = WidgetPalette.of(backgroundArgb = BLACK, textArgb = WidgetPalette.TEXT_AUTO)
        val onLight = WidgetPalette.of(backgroundArgb = WHITE, textArgb = WidgetPalette.TEXT_AUTO)
        assertNotEquals(onDark.text, onLight.text)
    }

    @Test
    fun `muted and surface derive from the text so they survive transparency`() {
        val palette = WidgetPalette.of(backgroundArgb = TRANSPARENT, systemDark = true)
        assertEquals(palette.text.red, palette.textMuted.red, 0.001f)
        assertTrue(palette.textMuted.alpha < palette.text.alpha)
        assertTrue(palette.surface.alpha < palette.textMuted.alpha)
    }

    @Test
    fun `the shipped default accent is the purple the app has always used`() {
        // The 17 to 18 migration hardcodes this as a signed literal, because a
        // migration must keep producing the same schema forever. If the constant here
        // ever changes, that literal is now telling upgrading users a different story.
        assertEquals(0xFFA78BFA.toInt(), WidgetPalette.DEFAULT_ACCENT)
        assertEquals(-5796870, WidgetPalette.DEFAULT_ACCENT)
    }

    @Test
    fun `the default background is fully transparent`() {
        assertEquals(0, WidgetPalette.DEFAULT_BACKGROUND)
        assertEquals(0f, Color(WidgetPalette.DEFAULT_BACKGROUND).alpha, 0.001f)
    }
}
