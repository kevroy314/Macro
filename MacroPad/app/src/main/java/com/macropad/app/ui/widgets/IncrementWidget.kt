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
import com.macropad.app.data.entity.WidgetSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Widget colors matching the app theme
private val WidgetBackground = Color(0xFF0A0A0A)
private val WidgetTextMuted = Color(0xFF9CA3AF)
private val WidgetGreen = Color(0xFF4ADE80)
private val WidgetGreenDim = Color(0xFF2D6B47)
private val WidgetCyan = Color(0xFF22D3EE)
private val WidgetCyanDim = Color(0xFF1A6B7C)
private val WidgetGold = Color(0xFFFBBF24)
private val WidgetGoldDim = Color(0xFF7B5F14)

class IncrementWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings: WidgetSettings = try {
            val app = context.applicationContext as MacroPadApplication
            app.repository.getWidgetSettings()
        } catch (e: Exception) {
            // Fallback to defaults if database access fails
            WidgetSettings()
        }

        provideContent {
            IncrementWidgetContent(settings)
        }
    }

    companion object {
        const val ACTION_ADD_MACRO = "com.macropad.app.ACTION_ADD_MACRO"
        const val EXTRA_PROTEIN = "protein"
        const val EXTRA_CARBS = "carbs"
        const val EXTRA_FAT = "fat"
    }
}

@Composable
fun IncrementWidgetContent(settings: WidgetSettings) {
    // Horizontal layout: P(+/-) | C(+/-) | F(+/-)
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp)
            .background(ColorProvider(WidgetBackground)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Protein column
        MacroColumn(
            label = "P",
            increment = settings.proteinIncrement,
            decrement = settings.proteinDecrement,
            colorUp = WidgetGreen,
            colorDown = WidgetGreenDim,
            macroType = "protein"
        )

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Carbs column
        MacroColumn(
            label = "C",
            increment = settings.carbsIncrement,
            decrement = settings.carbsDecrement,
            colorUp = WidgetCyan,
            colorDown = WidgetCyanDim,
            macroType = "carbs"
        )

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Fat column
        MacroColumn(
            label = "F",
            increment = settings.fatIncrement,
            decrement = settings.fatDecrement,
            colorUp = WidgetGold,
            colorDown = WidgetGoldDim,
            macroType = "fat"
        )
    }
}

@Composable
fun MacroColumn(
    label: String,
    increment: Int,
    decrement: Int,
    colorUp: Color,
    colorDown: Color,
    macroType: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(WidgetTextMuted),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        )

        Spacer(modifier = GlanceModifier.height(2.dp))

        // + button
        val incProtein = if (macroType == "protein") increment else 0
        val incCarbs = if (macroType == "carbs") increment else 0
        val incFat = if (macroType == "fat") increment else 0

        Box(
            modifier = GlanceModifier
                .size(36.dp, 24.dp)
                .cornerRadius(4.dp)
                .background(ColorProvider(colorUp))
                .clickable(
                    actionRunCallback<AddMacroAction>(
                        actionParametersOf(
                            ActionParameters.Key<Int>("protein") to incProtein,
                            ActionParameters.Key<Int>("carbs") to incCarbs,
                            ActionParameters.Key<Int>("fat") to incFat
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+$increment",
                style = TextStyle(
                    color = ColorProvider(Color.Black),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.height(2.dp))

        // - button
        val decProtein = if (macroType == "protein") -decrement else 0
        val decCarbs = if (macroType == "carbs") -decrement else 0
        val decFat = if (macroType == "fat") -decrement else 0

        Box(
            modifier = GlanceModifier
                .size(36.dp, 24.dp)
                .cornerRadius(4.dp)
                .background(ColorProvider(colorDown))
                .clickable(
                    actionRunCallback<AddMacroAction>(
                        actionParametersOf(
                            ActionParameters.Key<Int>("protein") to decProtein,
                            ActionParameters.Key<Int>("carbs") to decCarbs,
                            ActionParameters.Key<Int>("fat") to decFat
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "-$decrement",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

class AddMacroAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            val protein = parameters[ActionParameters.Key<Int>("protein")] ?: 0
            val carbs = parameters[ActionParameters.Key<Int>("carbs")] ?: 0
            val fat = parameters[ActionParameters.Key<Int>("fat")] ?: 0

            val app = context.applicationContext as MacroPadApplication

            // Perform database operation on IO dispatcher and ensure it completes
            withContext(Dispatchers.IO) {
                app.repository.addMacros(protein, carbs, fat)
            }

            // Force update MacroStatusWidget using state-based update mechanism
            // This changes the widget's preferences state, which forces Glance to
            // recognize a state change and re-run provideGlance with fresh data
            MacroStatusWidget.forceUpdateAll(context)
        } catch (e: Exception) {
            // Silently fail - widget will show stale data until next update
        }
    }
}

class IncrementWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = IncrementWidget()
}
