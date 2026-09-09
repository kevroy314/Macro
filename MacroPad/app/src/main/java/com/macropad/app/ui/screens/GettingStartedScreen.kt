package com.macropad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Widgets
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What someone needs to know in the first two minutes, and nothing else.
 *
 * Shown once on a fresh install and reachable afterwards from Settings. It is a
 * walkthrough rather than a wall of text because the things that make this app work —
 * putting a widget on the home screen, logging even the rough guesses, setting the day
 * to roll over when yours does — are habits, and a habit is easier to start when it is
 * asked for one at a time.
 */
private data class Step(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val action: String? = null
)

@Composable
fun GettingStartedScreen(
    onFinish: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiSetup: () -> Unit
) {
    val steps = remember {
        listOf(
            Step(
                icon = Icons.Default.Widgets,
                title = "Put it on your home screen",
                body = "This is the whole point. Long-press your home screen, pick " +
                    "MacroPad, and add a widget.\n\n" +
                    "The status widget shows today against your targets. The " +
                    "increment widget adds macros with one tap. The preset widget " +
                    "logs a saved meal without opening anything.\n\n" +
                    "Tracking you have to open an app for is tracking you stop doing."
            ),
            Step(
                icon = Icons.Default.PlaylistAddCheck,
                title = "Log everything, even the guesses",
                body = "A rough number beats no number. If you don't know what was in " +
                    "it, put in your best guess and move on — the totals are still " +
                    "far closer to the truth than a day with a hole in it.\n\n" +
                    "Save anything you eat often as a preset. After a week most days " +
                    "are a few taps.\n\n" +
                    "Nothing here is a streak you can break, and nothing scolds you."
            ),
            Step(
                icon = Icons.Default.Bedtime,
                title = "Make the day end when yours does",
                body = "If you eat at 1am, midnight is the wrong place to split the " +
                    "day — a late snack lands on tomorrow and both days read wrong.\n\n" +
                    "Set the reset hour to when you actually go to sleep. Everything " +
                    "logged before it counts towards the day before.",
                action = "Open Settings"
            ),
            Step(
                icon = Icons.Default.Flag,
                title = "Set targets you'll actually hit",
                body = "Targets are what the widgets count against, so they are worth " +
                    "a minute's thought.\n\n" +
                    "Pick numbers you can hit on an ordinary day, not a perfect one. " +
                    "You can change them whenever you like, and the history keeps " +
                    "what you logged regardless.",
                action = "Open Settings"
            ),
            Step(
                icon = Icons.Default.AutoAwesome,
                title = "Optional: let AI do the hard ones",
                body = "For the meals where nobody knows the macros — a restaurant " +
                    "plate, a takeaway, something someone else cooked — you can " +
                    "photograph it and have it estimated for you.\n\n" +
                    "It runs on a server you set up yourself, on your own machine. " +
                    "There is no MacroPad server and no account to create. The app is " +
                    "complete without it.",
                action = "How to set that up"
            )
        )
    }

    var index by remember { mutableIntStateOf(0) }
    val step = steps[index]
    val isLast = index == steps.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Progress as dots rather than "3 of 5": it reads as short, which it is.
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
                .padding(top = 32.dp)
        ) {
            Icon(
                step.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                step.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline
            )

            step.action?.let { label ->
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedButton(
                    onClick = {
                        // Finish first: coming back to a half-shown tour is worse than
                        // not seeing the rest of it.
                        onFinish()
                        if (step.icon == Icons.Default.AutoAwesome) {
                            onOpenAiSetup()
                        } else {
                            onOpenSettings()
                        }
                    }
                ) { Text(label) }
            }
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
