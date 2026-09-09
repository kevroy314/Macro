package com.macropad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macropad.app.ui.theme.CarbsColor
import com.macropad.app.ui.theme.FatColor
import com.macropad.app.ui.theme.ProteinColor

/** Where a step's action button sends you, and where Resume brings you back to. */
enum class GettingStartedTarget { DAY_RESET, TARGETS, AI_SETUP }

/**
 * What someone needs in the first two minutes, and nothing else.
 *
 * Shown once on a fresh install, and from Settings afterwards. Each step draws the
 * thing it is describing rather than describing it twice — a mocked-up widget is worth
 * more than a paragraph about widgets, and unlike a bundled screenshot it cannot go
 * stale when the app is restyled.
 */
private data class Step(
    val title: String,
    val body: String,
    val action: String? = null,
    val target: GettingStartedTarget? = null,
    val art: @Composable () -> Unit
)

@Composable
fun GettingStartedScreen(
    startIndex: Int = 0,
    onFinish: () -> Unit,
    onJumpTo: (GettingStartedTarget, Int) -> Unit
) {
    val steps = remember {
        listOf(
            Step(
                title = "Put a widget on your home screen",
                body = "MacroPad is built to be used without opening it. Long-press " +
                    "your home screen, pick MacroPad, and add one.\n\n" +
                    "Status shows today against your targets. Increment adds a macro " +
                    "in one tap. Preset logs a saved meal in one tap.\n\n" +
                    "Tracking you have to open an app for is tracking you stop doing.",
                art = { WidgetArt() }
            ),
            Step(
                title = "Log everything, even the guesses",
                body = "A rough number beats no number. If you don't know what was in " +
                    "it, put in your best guess and move on — the day is still far " +
                    "closer to the truth than one with a hole in it.\n\n" +
                    "Save anything you eat often as a preset. After a week, most days " +
                    "are a few taps.\n\n" +
                    "Nothing here is a streak you can break, and nothing scolds you.",
                art = { LogArt() }
            ),
            Step(
                title = "Make the day end when yours does",
                body = "If you eat at 1am, midnight is the wrong place to split the " +
                    "day — a late snack lands on tomorrow and both days read wrong.\n\n" +
                    "Set the reset hour to roughly when you go to sleep. Anything " +
                    "logged before it counts towards the day before.",
                action = "Set the reset hour",
                target = GettingStartedTarget.DAY_RESET,
                art = { DayResetArt() }
            ),
            Step(
                title = "Set targets you'll actually hit",
                body = "Targets are what the widgets count against, so they are worth " +
                    "a minute's thought.\n\n" +
                    "Pick numbers you can hit on an ordinary day, not a perfect one. " +
                    "Change them whenever you like — your history keeps what you " +
                    "logged either way.",
                action = "Set your targets",
                target = GettingStartedTarget.TARGETS,
                art = { TargetArt() }
            ),
            Step(
                title = "Optional: let AI handle the hard ones",
                body = "For meals where nobody knows the macros — a restaurant plate, " +
                    "a takeaway, something someone else cooked — photograph it and " +
                    "have it estimated.\n\n" +
                    "It runs on a server you set up yourself, on your own machine. " +
                    "There is no MacroPad server and no account to create.\n\n" +
                    "The app is complete without it. Skip this and nothing is missing.",
                action = "Read the setup guide",
                target = GettingStartedTarget.AI_SETUP,
                art = { AiArt() }
            )
        )
    }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, steps.lastIndex)) }
    val step = steps[index]
    val isLast = index == steps.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            steps.indices.forEach { i ->
                Spacer(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == index) 8.dp else 6.dp)
                        .background(
                            color = if (i <= index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp)
        ) {
            step.art()
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                // Explicit. Without it this inherits a default content colour that is
                // very nearly the background, and the heading silently disappears —
                // which is exactly how it shipped the first time.
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                step.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline
            )

            step.action?.let { label ->
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { step.target?.let { onJumpTo(it, index) } }) {
                    Text(label)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (index > 0) {
                OutlinedButton(onClick = { index-- }) { Text("Back") }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            if (!isLast) {
                OutlinedButton(onClick = onFinish) { Text("Skip") }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Button(onClick = { if (isLast) onFinish() else index++ }) {
                Text(if (isLast) "Start tracking" else "Next")
            }
        }
    }
}

// ----------------------------------------------------------------- illustrations
//
// Drawn rather than photographed. A bundled screenshot is stale the first time the
// app is restyled, and these cost nothing to keep in step with the real theme.

@Composable
private fun ArtFrame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** A mock of the status widget, so people know what they are looking for. */
@Composable
private fun WidgetArt() {
    ArtFrame {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(10.dp))
            MacroBar("P", 0.72f, ProteinColor)
            Spacer(modifier = Modifier.height(7.dp))
            MacroBar("C", 0.45f, CarbsColor)
            Spacer(modifier = Modifier.height(7.dp))
            MacroBar("F", 0.60f, FatColor)
        }
    }
}

@Composable
private fun MacroBar(label: String, fraction: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(16.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
    }
}

/** Three entries, one of them obviously a guess — the point of the step. */
@Composable
private fun LogArt() {
    ArtFrame {
        Column(modifier = Modifier.fillMaxWidth(0.72f)) {
            LogRow("Protein shake", "42P  9C  4F", MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            LogRow("Chicken + rice", "38P  55C  9F", MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            LogRow("Whatever that was", "~20P  ~40C  ~15F", MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun LogRow(name: String, macros: String, tint: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = tint, modifier = Modifier.weight(1f))
        Text(macros, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** A day bar split at 4am rather than midnight, with a snack on the correct side. */
@Composable
private fun DayResetArt() {
    ArtFrame {
        Column(modifier = Modifier.fillMaxWidth(0.78f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Day ends at 4am",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth().height(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(0.83f)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                )
                Spacer(modifier = Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .weight(0.17f)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.weight(1f))
                Text("1am snack ↑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun TargetArt() {
    ArtFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Flag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                TargetRow("Protein", "180 g", ProteinColor)
                Spacer(modifier = Modifier.height(6.dp))
                TargetRow("Carbs", "220 g", CarbsColor)
                Spacer(modifier = Modifier.height(6.dp))
                TargetRow("Fat", "70 g", FatColor)
            }
        }
    }
}

@Composable
private fun TargetRow(name: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(70.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AiArt() {
    ArtFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text("→", fontSize = 22.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.width(14.dp))
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text("→", fontSize = 22.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text("31P  50C  9F", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("405 kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
