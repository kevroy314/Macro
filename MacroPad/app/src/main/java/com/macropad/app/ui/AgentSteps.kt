package com.macropad.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.macropad.app.net.AiStep

private val gson = Gson()

/** Parses the stored JSON. Bad data shows nothing rather than taking the screen down. */
fun parseSteps(json: String?): List<AiStep> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        gson.fromJson(json, object : TypeToken<List<AiStep>>() {}.type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * What the agent did, on the way to its answer.
 *
 * Open while it works, so a long run is legible rather than looking stuck, and
 * collapsed once it finishes — at that point the answer is the point, and the working
 * is there for anyone who wants to see how much went into it.
 */
@Composable
fun AgentSteps(
    stepsJson: String,
    running: Boolean,
    liveLine: String = "",
    modifier: Modifier = Modifier
) {
    val steps = remember(stepsJson) { parseSteps(stepsJson) }
    if (steps.isEmpty() && liveLine.isBlank()) return

    var expanded by remember { mutableStateOf(running) }

    // Collapse itself when the run ends, but leave a deliberate expand alone.
    var wasRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (wasRunning && !running) expanded = false
        if (!wasRunning && running) expanded = true
        wasRunning = running
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = when {
                    running && liveLine.isNotBlank() && !expanded -> liveLine
                    steps.isEmpty() -> "Working"
                    steps.size == 1 -> "1 step"
                    else -> "${steps.size} steps"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide the steps" else "Show the steps",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                steps.forEach { step ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            step.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                if (running && liveLine.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        liveLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
