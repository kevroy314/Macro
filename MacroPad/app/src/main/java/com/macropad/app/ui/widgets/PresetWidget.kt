package com.macropad.app.ui.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.macropad.app.MacroPadApplication
import com.macropad.app.data.entity.MacroPreset

// Widget colors matching the app theme
private val WidgetBackground = Color(0xFF0A0A0A)
private val WidgetTextMuted = Color(0xFF9CA3AF)
private val WidgetAccent = Color(0xFFA78BFA)
private val WidgetSurface = Color(0xFF1A1A1A)

class PresetWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val presets: List<MacroPreset> = try {
            val app = context.applicationContext as MacroPadApplication
            app.repository.getAllPresets()
        } catch (e: Exception) {
            // Fallback to empty list if database access fails
            emptyList()
        }

        provideContent {
            PresetWidgetContent(presets.take(4))
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
            Text(
                text = "No presets",
                style = TextStyle(
                    color = ColorProvider(WidgetTextMuted),
                    fontSize = 11.sp
                )
            )
        } else {
            presets.forEach { preset ->
                PresetButton(preset)
                Spacer(modifier = GlanceModifier.height(4.dp))
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
            val preset = app.repository.getPresetById(presetId) ?: return

            app.repository.applyPreset(preset)

            // Update all widgets
            MacroStatusWidget().updateAll(context)
            IncrementWidget().updateAll(context)
            PresetWidget().updateAll(context)
        } catch (e: Exception) {
            // Silently fail - widget will show stale data until next update
        }
    }
}

class PresetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PresetWidget()
}
