package com.macropad.app.ui.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.macropad.app.MainActivity
import com.macropad.app.MacroPadApplication
import com.macropad.app.data.entity.MacroPreset
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Widget colors matching the app theme
private val WidgetBackground = Color(0xFF0A0A0A)
private val WidgetTextMuted = Color(0xFF9CA3AF)
private val WidgetAccent = Color(0xFFA78BFA)
private val WidgetSurface = Color(0xFF1A1A1A)

// Key for storing preset data in widget preferences
private val KEY_PRESETS_JSON = stringPreferencesKey("presets_json")

// Maximum number of presets to display
private const val MAX_PRESETS = 128

// Gson instance for JSON serialization
private val gson = Gson()

class PresetWidget : GlanceAppWidget() {

    // Use PreferencesGlanceStateDefinition to enable state-based updates
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Fetch fresh data from database and update preferences BEFORE rendering
        val presets: List<MacroPreset> = try {
            val app = context.applicationContext as MacroPadApplication
            app.repository.getAllPresets().take(MAX_PRESETS)
        } catch (e: Exception) {
            emptyList()
        }

        // Serialize and store presets in widget preferences
        val presetsJson = gson.toJson(presets)
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply {
                this[KEY_PRESETS_JSON] = presetsJson
            }
        }

        provideContent {
            // Read from the widget's preferences state
            val prefs = currentState<Preferences>()
            val storedJson = prefs[KEY_PRESETS_JSON]
            val displayPresets = if (storedJson != null) {
                try {
                    val type = object : TypeToken<List<MacroPreset>>() {}.type
                    gson.fromJson<List<MacroPreset>>(storedJson, type)
                } catch (e: Exception) {
                    presets
                }
            } else {
                presets
            }

            PresetWidgetContent(displayPresets)
        }
    }

    companion object {
        /**
         * Update the widget with fresh data from the database.
         * This writes data INTO the widget's preferences, which Glance observes.
         */
        suspend fun forceUpdate(context: Context, glanceId: GlanceId) {
            // Fetch fresh data from database
            val presets: List<MacroPreset> = try {
                val app = context.applicationContext as MacroPadApplication
                app.repository.getAllPresets().take(MAX_PRESETS)
            } catch (e: Exception) {
                emptyList()
            }

            // Serialize and write to widget preferences
            val presetsJson = gson.toJson(presets)
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_PRESETS_JSON] = presetsJson
                }
            }

            // Trigger the actual update
            PresetWidget().update(context, glanceId)
        }

        /**
         * Force update all instances of this widget
         */
        suspend fun forceUpdateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(PresetWidget::class.java)
            glanceIds.forEach { glanceId ->
                forceUpdate(context, glanceId)
            }
        }
    }
}

@Composable
fun PresetWidgetContent(presets: List<MacroPreset>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(8.dp)
            .background(ColorProvider(WidgetBackground)),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Presets",
            style = TextStyle(
                color = ColorProvider(WidgetAccent),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        if (presets.isEmpty()) {
            // Empty state - clickable to open Presets screen
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No presets - tap to add",
                    style = TextStyle(
                        color = ColorProvider(WidgetTextMuted),
                        fontSize = 11.sp
                    )
                )
            }
        } else {
            // Scrollable list of presets
            LazyColumn(
                modifier = GlanceModifier.fillMaxSize()
            ) {
                items(presets, itemId = { it.id }) { preset ->
                    Column {
                        PresetButton(preset)
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PresetButton(preset: MacroPreset) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(32.dp)
            .cornerRadius(6.dp)
            .background(ColorProvider(WidgetSurface))
            .clickable(
                actionRunCallback<ApplyPresetAction>(
                    actionParametersOf(
                        ActionParameters.Key<Long>("presetId") to preset.id
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = preset.name,
                style = TextStyle(
                    color = ColorProvider(Color(0xFFE0E0E0)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = "P${preset.proteinG}",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF4ADE80)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

class ApplyPresetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            val presetId = parameters[ActionParameters.Key<Long>("presetId")] ?: return

            val app = context.applicationContext as MacroPadApplication

            // Perform database operations on IO dispatcher and ensure they complete
            withContext(Dispatchers.IO) {
                val preset = app.repository.getPresetById(presetId) ?: return@withContext
                app.repository.applyPreset(preset)
            }

            // Force update MacroStatusWidget using state-based update mechanism
            MacroStatusWidget.forceUpdateAll(context)
        } catch (e: Exception) {
            // Silently fail - widget will show stale data until next update
        }
    }
}

class PresetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PresetWidget()
}
