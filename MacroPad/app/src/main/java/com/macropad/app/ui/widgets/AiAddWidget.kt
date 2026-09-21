package com.macropad.app.ui.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.compose.ui.unit.DpSize
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.macropad.app.MainActivity


/**
 * One-tap shortcut into the AI entry form. Deliberately does nothing else: the point
 * is to get from "I'm looking at this meal" to the camera in a single press.
 */
class AiAddWidget : GlanceAppWidget() {

    // One cell is the common case, so it gets a layout of its own rather than a
    // two-cell layout squeezed until the label wraps.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(56.dp, 56.dp),
            DpSize(120.dp, 56.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val storedPalette = widgetPalette(context)

        provideContent {
            val palette = glancePalette(storedPalette)
            val compact = LocalSize.current.width < 100.dp

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    // At one cell the button IS the widget, so there is nothing for an
                    // outer margin to separate it from — the launcher already leaves a
                    // gap between cells. Insetting again just shrank the tap target.
                    .padding(if (compact) 0.dp else 8.dp)
                    .background(ColorProvider(palette.background))
                    .clickable(actionRunCallback<OpenAiEntryAction>()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = if (compact) {
                        // A 12%-white chip vanishes against a dark wallpaper, and at one
                        // cell there is nothing else on screen to say where the button
                        // is. Filling the cell with the accent makes it read as a
                        // launcher icon, which is how it is actually used.
                        GlanceModifier
                            .fillMaxSize()
                            .cornerRadius(18.dp)
                            .background(ColorProvider(palette.accent))
                    } else {
                        GlanceModifier
                            .cornerRadius(10.dp)
                            .background(ColorProvider(palette.surface))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    if (compact) {
                        // "AI" alone says nothing about what the button does. One cell
                        // will not take "AI Food Add" on a line, so it breaks where the
                        // phrase already breaks: the label, then what it acts on.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "AI",
                                style = TextStyle(
                                    color = ColorProvider(palette.onAccent),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Food Add",
                                style = TextStyle(
                                    color = ColorProvider(palette.onAccent),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    } else {
                        Text(
                            text = "AI Food Add",
                            style = TextStyle(
                                color = ColorProvider(palette.accent),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                if (!compact) {
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Text(
                        text = "Photo + note",
                        style = TextStyle(color = ColorProvider(palette.textMuted), fontSize = 11.sp)
                    )
                }
            }
        }
    }
}

/**
 * Launched from the widget. Uses an explicit intent rather than actionStartActivity
 * so the route extra survives onNewIntent when the app is already open.
 */
class OpenAiEntryAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_AI_ENTRY)
        }
        context.startActivity(intent)
    }
}

class AiAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AiAddWidget()
}
