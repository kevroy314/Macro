package com.macropad.app.ui.widgets

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.LocalContext
import androidx.glance.currentState
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
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

private val KEY_ACCENT = intPreferencesKey("palette_accent")
private val KEY_BACKGROUND = intPreferencesKey("palette_background")
private val KEY_TEXT = intPreferencesKey("palette_text")

/**
 * The palette to draw with, read from inside the composition.
 *
 * This is the part that has to be read here rather than in provideGlance. When the
 * launcher already has a live Glance session for a widget, an update recomposes that
 * session — everything provideGlance computed before provideContent was captured when
 * the session started and is not recomputed. A palette read up there therefore stays
 * on whatever the colours were the last time the widget was created, which is why a
 * colour change used to appear one save late: the widget was showing the previous
 * settings, and the next save was what finally pushed them out.
 *
 * Glance state, on the other hand, is observed. [refreshAllWidgets] writes the chosen
 * colours into it, so this reads them and recomposes. The value passed in is used
 * until then, which covers the first render of a newly placed widget.
 */
@Composable
fun glancePalette(fallback: WidgetPalette.Palette): WidgetPalette.Palette {
    val prefs = currentState<Preferences>()
    val accent = prefs[KEY_ACCENT] ?: return fallback
    val systemDark = (LocalContext.current.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return WidgetPalette.of(
        accentArgb = accent,
        backgroundArgb = prefs[KEY_BACKGROUND] ?: WidgetPalette.DEFAULT_BACKGROUND,
        textArgb = prefs[KEY_TEXT] ?: WidgetPalette.TEXT_AUTO,
        systemDark = systemDark
    )
}

/**
 * Repaint every widget after an appearance change.
 *
 * The colours go into each widget's own state rather than being left in the database
 * for the widget to fetch, because state is the only thing a running Glance session
 * actually watches. See [glancePalette].
 */
suspend fun refreshAllWidgets(context: Context) {
    val settings = try {
        (context.applicationContext as MacroPadApplication).repository.getWidgetSettings()
    } catch (e: Exception) {
        null
    }
    MacroStatusWidget.forceUpdateAll(context)
    PresetWidget.forceUpdateAll(context)
    pushPalette(context, IncrementWidget::class.java, IncrementWidget(), settings)
    pushPalette(context, AiAddWidget::class.java, AiAddWidget(), settings)
    // The two data widgets have already been updated above, but their sessions need
    // the colours in state for the same reason; write them and let the next update
    // through. Doing it after their forceUpdateAll keeps one update per widget.
    pushPalette(context, MacroStatusWidget::class.java, MacroStatusWidget(), settings)
    pushPalette(context, PresetWidget::class.java, PresetWidget(), settings)
}

private suspend fun <W : GlanceAppWidget> pushPalette(
    context: Context,
    javaClass: Class<W>,
    widget: W,
    settings: com.macropad.app.data.entity.WidgetSettings?
) {
    if (settings == null) return
    val ids = GlanceAppWidgetManager(context).getGlanceIds(javaClass)
    ids.forEach { id ->
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply {
                this[KEY_ACCENT] = settings.accentColor
                this[KEY_BACKGROUND] = settings.widgetBackgroundColor
                this[KEY_TEXT] = settings.widgetTextColor
            }
        }
        widget.update(context, id)
    }
}
