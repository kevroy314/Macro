package com.macropad.app.ui.widgets

import android.content.Context
import android.content.res.Configuration
import com.macropad.app.MacroPadApplication
import com.macropad.app.ui.theme.WidgetPalette

/**
 * The colours every widget draws with, read once per render.
 *
 * Widgets used to each keep their own private copies of the same four hex values,
 * which is how three of them ended up able to disagree.
 */
suspend fun widgetPalette(context: Context): WidgetPalette.Palette {
    val systemDark = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return try {
        val settings = (context.applicationContext as MacroPadApplication)
            .repository.getWidgetSettings()
        WidgetPalette.of(
            accentArgb = settings.accentColor,
            backgroundArgb = settings.widgetBackgroundColor,
            textArgb = settings.widgetTextColor,
            systemDark = systemDark
        )
    } catch (e: Exception) {
        // A widget that cannot reach the database still has to draw something.
        WidgetPalette.of(systemDark = systemDark)
    }
}
