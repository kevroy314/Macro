package com.macropad.app.ui.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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

private val WidgetBackground = Color(0xFF0A0A0A)
private val WidgetAccent = Color(0xFFA78BFA)
private val WidgetSurface = Color(0xFF1A1A1A)
private val WidgetTextMuted = Color(0xFF9CA3AF)

/**
 * One-tap shortcut into the AI entry form. Deliberately does nothing else: the point
 * is to get from "I'm looking at this meal" to the camera in a single press.
 */
class AiAddWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .background(ColorProvider(WidgetBackground))
                    .clickable(actionRunCallback<OpenAiEntryAction>()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = GlanceModifier
                        .cornerRadius(10.dp)
                        .background(ColorProvider(WidgetSurface))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "AI Add",
                        style = TextStyle(
                            color = ColorProvider(WidgetAccent),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Spacer(modifier = GlanceModifier.height(6.dp))
                Text(
                    text = "Photo + note",
                    style = TextStyle(color = ColorProvider(WidgetTextMuted), fontSize = 11.sp)
                )
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
