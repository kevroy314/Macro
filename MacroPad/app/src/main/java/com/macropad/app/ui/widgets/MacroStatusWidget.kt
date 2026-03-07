package com.macropad.app.ui.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.macropad.app.MainActivity
import com.macropad.app.MacroPadApplication
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroTarget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// Widget colors matching the app theme
private val WidgetBackground = Color(0xFF0A0A0A)
private val WidgetTextMuted = Color(0xFF9CA3AF)
private val WidgetAccent = Color(0xFFA78BFA)
private val WidgetGreen = Color(0xFF4ADE80)
private val WidgetCyan = Color(0xFF22D3EE)
private val WidgetGold = Color(0xFFFBBF24)
private val WidgetPink = Color(0xFFF472B6)
private val WidgetRed = Color(0xFFEF4444)

// Keys for storing macro data in widget preferences
private val KEY_PROTEIN = intPreferencesKey("protein")
private val KEY_CARBS = intPreferencesKey("carbs")
private val KEY_FAT = intPreferencesKey("fat")
private val KEY_TARGET_PROTEIN = intPreferencesKey("target_protein")
private val KEY_TARGET_CARBS = intPreferencesKey("target_carbs")
private val KEY_TARGET_FAT = intPreferencesKey("target_fat")

class MacroStatusWidget : GlanceAppWidget() {

    // Use PreferencesGlanceStateDefinition to enable state-based updates
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(120.dp, 48.dp),   // Horizontal compact band
            DpSize(180.dp, 48.dp),   // Horizontal with more space
            DpSize(120.dp, 120.dp),  // Square layout
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // ALWAYS fetch fresh data from database and update preferences BEFORE rendering
        // This ensures the widget shows correct data on first load AND on updates
        val (macros, target) = try {
            val app = context.applicationContext as MacroPadApplication
            val repository = app.repository
            val todayDate = repository.getAdjustedTodayDate()
            val m = repository.getTodayMacros() ?: DailyMacro(date = todayDate)
            val t = repository.getTarget()
            Pair(m, t)
        } catch (e: Exception) {
            Pair(DailyMacro(date = DailyMacro.today()), MacroTarget())
        }

        // Update the preferences state with fresh data
        // This is done BEFORE provideContent so Glance sees the data
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply {
                this[KEY_PROTEIN] = macros.proteinG
                this[KEY_CARBS] = macros.carbsG
                this[KEY_FAT] = macros.fatG
                this[KEY_TARGET_PROTEIN] = target.proteinG
                this[KEY_TARGET_CARBS] = target.carbsG
                this[KEY_TARGET_FAT] = target.fatG
            }
        }

        provideContent {
            // Read from the widget's preferences state
            val prefs = currentState<Preferences>()

            val protein = prefs[KEY_PROTEIN] ?: macros.proteinG
            val carbs = prefs[KEY_CARBS] ?: macros.carbsG
            val fat = prefs[KEY_FAT] ?: macros.fatG
            val targetProtein = prefs[KEY_TARGET_PROTEIN] ?: target.proteinG
            val targetCarbs = prefs[KEY_TARGET_CARBS] ?: target.carbsG
            val targetFat = prefs[KEY_TARGET_FAT] ?: target.fatG

            val displayMacros = DailyMacro(
                date = "",
                proteinG = protein,
                carbsG = carbs,
                fatG = fat
            )
            val displayTarget = MacroTarget(
                proteinG = targetProtein,
                carbsG = targetCarbs,
                fatG = targetFat
            )

            val size = LocalSize.current
            if (size.height < 80.dp) {
                MacroStatusHorizontalContent(displayMacros, displayTarget)
            } else {
                MacroStatusVerticalContent(displayMacros, displayTarget)
            }
        }
    }

    companion object {
        /**
         * Update the widget with fresh data from the database.
         * This writes data INTO the widget's preferences, which Glance observes.
         */
        suspend fun forceUpdate(context: Context, glanceId: GlanceId) {
            // Fetch fresh data from database
            val (macros, target) = try {
                val app = context.applicationContext as MacroPadApplication
                val repository = app.repository
                val todayDate = repository.getAdjustedTodayDate()
                val m = repository.getTodayMacros() ?: DailyMacro(date = todayDate)
                val t = repository.getTarget()
                Pair(m, t)
            } catch (e: Exception) {
                Pair(DailyMacro(date = DailyMacro.today()), MacroTarget())
            }

            // Write data INTO the widget's preferences state
            // This is what triggers Glance to recognize a state change
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_PROTEIN] = macros.proteinG
                    this[KEY_CARBS] = macros.carbsG
                    this[KEY_FAT] = macros.fatG
                    this[KEY_TARGET_PROTEIN] = target.proteinG
                    this[KEY_TARGET_CARBS] = target.carbsG
                    this[KEY_TARGET_FAT] = target.fatG
                }
            }

            // Now trigger the actual update - Glance will see the preferences changed
            MacroStatusWidget().update(context, glanceId)
        }

        /**
         * Force update all instances of this widget
         */
        suspend fun forceUpdateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(MacroStatusWidget::class.java)
            glanceIds.forEach { glanceId ->
                forceUpdate(context, glanceId)
            }
        }
    }
}

@Composable
fun MacroStatusHorizontalContent(macros: DailyMacro, target: MacroTarget) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(ColorProvider(WidgetBackground))
            .clickable(actionStartActivity<MainActivity>()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Protein
        MacroCompact("P", macros.proteinG, target.proteinG, WidgetGreen)
        Spacer(modifier = GlanceModifier.width(6.dp))

        // Carbs
        MacroCompact("C", macros.carbsG, target.carbsG, WidgetCyan)
        Spacer(modifier = GlanceModifier.width(6.dp))

        // Fat
        MacroCompact("F", macros.fatG, target.fatG, WidgetGold)
        Spacer(modifier = GlanceModifier.width(6.dp))

        // Calories
        Text(
            text = "${macros.calories}",
            style = TextStyle(
                color = ColorProvider(WidgetPink),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
fun MacroCompact(label: String, current: Int, target: Int, color: Color) {
    val isOver = current > target
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(WidgetTextMuted),
                fontSize = 9.sp
            )
        )
        Text(
            text = "$current",
            style = TextStyle(
                color = ColorProvider(if (isOver) WidgetRed else color),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
fun MacroStatusVerticalContent(macros: DailyMacro, target: MacroTarget) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(10.dp)
            .background(ColorProvider(WidgetBackground))
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Today",
            style = TextStyle(
                color = ColorProvider(WidgetAccent),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        MacroRow("P", macros.proteinG, target.proteinG, ColorProvider(WidgetGreen))
        Spacer(modifier = GlanceModifier.height(3.dp))
        MacroRow("C", macros.carbsG, target.carbsG, ColorProvider(WidgetCyan))
        Spacer(modifier = GlanceModifier.height(3.dp))
        MacroRow("F", macros.fatG, target.fatG, ColorProvider(WidgetGold))

        Spacer(modifier = GlanceModifier.height(6.dp))

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "${macros.calories} cal",
                style = TextStyle(
                    color = ColorProvider(WidgetPink),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
fun MacroRow(label: String, current: Int, target: Int, color: ColorProvider) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(WidgetTextMuted),
                fontSize = 11.sp
            ),
            modifier = GlanceModifier.width(20.dp)
        )
        Text(
            text = "$current/$target",
            style = TextStyle(
                color = if (current > target) ColorProvider(WidgetRed) else color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

class MacroStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MacroStatusWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Handle custom update action
        if (intent.action == ACTION_UPDATE_WIDGET) {
            MainScope().launch {
                // Use force update to ensure state change is recognized
                MacroStatusWidget.forceUpdateAll(context)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "com.macropad.app.UPDATE_STATUS_WIDGET"

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, MacroStatusWidgetReceiver::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }
}
