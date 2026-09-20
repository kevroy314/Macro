package com.macropad.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macropad.app.data.entity.WidgetSettings
import com.macropad.app.ui.theme.WidgetPalette

/** A spread wide enough to be worth choosing from, without being a colour wheel. */
private val ACCENTS = listOf(
    0xFFA78BFA, 0xFF7877C6, 0xFF4ADE80, 0xFF22D3EE,
    0xFFFBBF24, 0xFFFB923C, 0xFFF472B6, 0xFFEF4444,
    0xFF94A3B8, 0xFFFFFFFF
).map { it.toInt() }

/** Grounds for the widget, from invisible to solid. */
private val BACKGROUNDS = listOf(
    0x00000000, 0xFF000000.toInt(), 0xFF0A0A0A.toInt(), 0xFF1A1A1A.toInt(),
    0xFF262335.toInt(), 0xFFF2F2F2.toInt(), 0xFFFFFFFF.toInt()
)

/**
 * Accent and widget background, with alpha.
 *
 * Widgets are the reason this exists — they sit on the home screen next to everything
 * else the user chose, so a fixed purple on a fixed black rectangle is the one part of
 * the app that cannot blend in. The same accent drives the app so the two stay in step.
 */
@Composable
fun AppearanceCard(
    settings: WidgetSettings,
    onSave: (WidgetSettings) -> Unit
) {
    var advanced by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // A live sample, because the widget itself is on the home screen where
                // you cannot see it while you are choosing colours for it.
                WidgetPreview(settings = settings, systemDark = isSystemInDarkTheme())
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text("Accent", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Used by the app and the widgets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))
            Swatches(
                colors = ACCENTS,
                selected = settings.accentColor,
                onPick = { onSave(settings.copy(accentColor = it)) }
            )

            Spacer(modifier = Modifier.height(18.dp))
            Text("Widget background", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Transparent lets your wallpaper through.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))
            Swatches(
                colors = BACKGROUNDS,
                selected = settings.widgetBackgroundColor,
                // Keep the alpha the user already chose when they change the hue —
                // otherwise picking a colour silently makes the widget opaque again.
                onPick = { picked ->
                    val alpha = (settings.widgetBackgroundColor ushr 24) and 0xFF
                    val keep = if (picked == 0x00000000) 0 else alpha
                    onSave(settings.copy(widgetBackgroundColor = (keep shl 24) or (picked and 0x00FFFFFF)))
                }
            )

            Spacer(modifier = Modifier.height(14.dp))
            val alpha = ((settings.widgetBackgroundColor ushr 24) and 0xFF)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Opacity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(72.dp)
                )
                Slider(
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    value = alpha / 255f,
                    onValueChange = { fraction ->
                        val a = (fraction * 255).toInt().coerceIn(0, 255)
                        onSave(
                            settings.copy(
                                widgetBackgroundColor =
                                    (a shl 24) or (settings.widgetBackgroundColor and 0x00FFFFFF)
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${(alpha * 100) / 255}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Advanced colour settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (advanced) "Hide" else "Show",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = advanced) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Widget text", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Normally worked out from the background — light text on a dark " +
                            "one, dark on a light one. On a transparent widget there is " +
                            "nothing to measure, so it follows your system theme. Override " +
                            "it here if that guesses wrong against your wallpaper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                onSave(settings.copy(widgetTextColor = WidgetPalette.TEXT_AUTO))
                            }
                        ) {
                            Text(
                                "Auto",
                                color = if (settings.widgetTextColor == WidgetPalette.TEXT_AUTO) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                        Swatches(
                            colors = listOf(0xFFF2F2F2.toInt(), 0xFF16161A.toInt(), 0xFF9CA3AF.toInt()),
                            selected = settings.widgetTextColor,
                            onPick = { onSave(settings.copy(widgetTextColor = it)) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    // Wraps: ten swatches at 32dp overflow a phone's width, and the last one simply
    // vanished off the edge rather than moving to a second line.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        colors.forEach { argb ->
            // Compare the hue only, so moving the alpha slider does not make the
            // chosen swatch look unchosen. The transparent swatch is the exception:
            // it is selected precisely when there is no opacity left.
            val selectedAlpha = (selected ushr 24) and 0xFF
            val isSelected = if (argb == 0x00000000) {
                selectedAlpha == 0
            } else {
                (argb and 0x00FFFFFF) == (selected and 0x00FFFFFF) && selectedAlpha > 0
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        // Transparent needs to look like something, so it shows as a
                        // ring rather than an invisible gap in the row.
                        if (argb == 0x00000000) Color.Transparent else Color(argb),
                        CircleShape
                    )
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
                    .clickable { onPick(argb) },
                contentAlignment = Alignment.Center
            ) {
                if (argb == 0x00000000) {
                    Text(
                        "0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/** A live sample of what the widget will look like, above the pickers. */
@Composable
fun WidgetPreview(settings: WidgetSettings, systemDark: Boolean) {
    val palette = WidgetPalette.of(
        accentArgb = settings.accentColor,
        backgroundArgb = settings.widgetBackgroundColor,
        textArgb = settings.widgetTextColor,
        systemDark = systemDark
    )
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        // A checkerboard stands in for the wallpaper. Without it a transparent widget
        // is previewed against the app's own dark card, which is nothing like where it
        // actually lives — dark text on a light wallpaper looked broken here while
        // being exactly right on the home screen.
        Column(Modifier.matchParentSize()) {
            repeat(6) { row ->
                Row(Modifier.weight(1f)) {
                    repeat(6) { col ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    if ((row + col) % 2 == 0) Color(0xFF6B7280) else Color(0xFF9CA3AF)
                                )
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier.matchParentSize().background(palette.background)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("P 118", style = MaterialTheme.typography.labelSmall, color = palette.text)
            Text("C 132", style = MaterialTheme.typography.labelSmall, color = palette.text)
            Text("F 49", style = MaterialTheme.typography.labelSmall, color = palette.text)
            Text(
                "1441",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = palette.accent
            )
        }
    }
}
