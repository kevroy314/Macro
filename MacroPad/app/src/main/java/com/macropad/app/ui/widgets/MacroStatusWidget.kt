package com.macropad.app.ui.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.macropad.app.MainActivity
import com.macropad.app.MacroPadApplication
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroTarget

// Widget colors matching the app theme
private val WidgetBackground = Color(0xFF0A0A0A)
private val WidgetSurface = Color(0xFF1A1A1A)
private val WidgetTextPrimary = Color(0xFFE0E0E0)
private val WidgetTextMuted = Color(0xFF9CA3AF)
private val WidgetAccent = Color(0xFFA78BFA)
private val WidgetGreen = Color(0xFF4ADE80)
private val WidgetCyan = Color(0xFF22D3EE)
private val WidgetGold = Color(0xFFFBBF24)
private val WidgetPink = Color(0xFFF472B6)
private val WidgetRed = Color(0xFFEF4444)

class MacroStatusWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(120.dp, 48.dp),   // Horizontal compact band
            DpSize(180.dp, 48.dp),   // Horizontal with more space
            DpSize(120.dp, 120.dp),  // Square layout
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val todayMacros: DailyMacro
        val target: MacroTarget

        try {
            val app = context.applicationContext as MacroPadApplication
            val repository = app.repository
            todayMacros = repository.getTodayMacros() ?: DailyMacro(date = DailyMacro.today())
            target = repository.getTarget()
        } catch (e: Exception) {
            // Fallback to defaults if database access fails
            todayMacros = DailyMacro(date = DailyMacro.today())
            target = MacroTarget()
        }

        provideContent {
            val size = LocalSize.current
            if (size.height < 80.dp) {
                // Horizontal band layout
                MacroStatusHorizontalContent(todayMacros, target)
            } else {
                // Square/vertical layout
                MacroStatusVerticalContent(todayMacros, target)
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
}
