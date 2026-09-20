package com.macropad.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The colours a widget draws with, worked out from what the user chose.
 *
 * Pure, because the interesting part is the contrast rule and that is easy to get
 * wrong in a way nobody notices until their wallpaper changes. A widget with a
 * transparent background sits on an unknown surface, so "what colour should the text
 * be" genuinely has to be reasoned about rather than hardcoded.
 */
object WidgetPalette {

    /** Defaults match what the widgets used before any of this was configurable. */
    const val DEFAULT_ACCENT = 0xFFA78BFA.toInt()

    /** Fully transparent: the wallpaper shows through, which is the point. */
    const val DEFAULT_BACKGROUND = 0x00000000

    /** Sentinel for "work the text colour out for me". Zero is fully transparent and
     *  therefore never a colour anyone would deliberately choose for text. */
    const val TEXT_AUTO = 0

    private val LIGHT_TEXT = Color(0xFFF2F2F2)
    private val DARK_TEXT = Color(0xFF16161A)

    /**
     * Below this the background cannot be relied on to cover what is behind it, so its
     * own colour says nothing useful about what the text will sit on.
     */
    private const val OPAQUE_ENOUGH = 0.45f

    data class Palette(
        val accent: Color,
        val background: Color,
        val text: Color,
        /** Secondary text — labels, units. The main colour, faded toward the ground. */
        val textMuted: Color,
        /** Chips and bar tracks. Derived so it works on any background. */
        val surface: Color
    )

    fun of(
        accentArgb: Int = DEFAULT_ACCENT,
        backgroundArgb: Int = DEFAULT_BACKGROUND,
        textArgb: Int = TEXT_AUTO,
        systemDark: Boolean = true
    ): Palette {
        val background = Color(backgroundArgb)
        val text = if (textArgb == TEXT_AUTO) {
            autoTextColor(backgroundArgb, systemDark)
        } else {
            Color(textArgb)
        }
        return Palette(
            accent = Color(accentArgb),
            background = background,
            text = text,
            textMuted = text.copy(alpha = 0.65f),
            // Tied to the text rather than the background: on a transparent widget the
            // background contributes nothing, and a chip has to be visible against
            // whatever is actually behind it.
            surface = text.copy(alpha = 0.12f)
        )
    }

    /**
     * Light text on dark, dark text on light.
     *
     * When the background is too transparent to count on, there is nothing to measure,
     * so the system theme is the best guess available.
     */
    fun autoTextColor(backgroundArgb: Int, systemDark: Boolean): Color {
        val alpha = ((backgroundArgb ushr 24) and 0xFF) / 255f
        if (alpha < OPAQUE_ENOUGH) {
            return if (systemDark) LIGHT_TEXT else DARK_TEXT
        }
        return if (relativeLuminance(backgroundArgb) > 0.5f) DARK_TEXT else LIGHT_TEXT
    }

    /** WCAG relative luminance, so "is this light" matches how an eye sees it. */
    fun relativeLuminance(argb: Int): Float {
        fun channel(shift: Int): Float {
            val raw = ((argb ushr shift) and 0xFF) / 255f
            return if (raw <= 0.03928f) {
                raw / 12.92f
            } else {
                ((raw + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
            }
        }
        return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
    }
}
