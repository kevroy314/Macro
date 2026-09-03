package com.macropad.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macropad.app.data.entity.AiThread
import kotlinx.coroutines.flow.Flow

/**
 * Host for the two AI features. They share a tab strip rather than two bottom-nav
 * slots: planning threads talk about the same day the estimates are logging into.
 */
@Composable
fun AiHomeScreen(
    threadsFlow: Flow<List<AiThread>>,
    onOpenThread: (String) -> Unit,
    onNewThread: () -> Unit,
    onDeleteThread: suspend (String) -> Unit,
    estimates: @Composable () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Estimates") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Planning") }
            )
        }

        when (selectedTab) {
            0 -> estimates()
            else -> Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Planning",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    FloatingActionButton(
                        onClick = onNewThread,
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New plan")
                    }
                }
                PlanningScreen(
                    threadsFlow = threadsFlow,
                    onOpenThread = onOpenThread,
                    onNewThread = onNewThread,
                    onDeleteThread = onDeleteThread
                )
            }
        }
    }
}
