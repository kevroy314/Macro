package com.macropad.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.PresetDisplaySettings
import com.macropad.app.data.entity.PresetSortMode
import com.macropad.app.ui.theme.*
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    presetsFlow: Flow<List<MacroPreset>>,
    displaySettingsFlow: Flow<PresetDisplaySettings?>,
    onSavePreset: (MacroPreset) -> Unit,
    onDeletePreset: (MacroPreset) -> Unit,
    onApplyPreset: (MacroPreset) -> Unit,
    onSaveDisplaySettings: (PresetDisplaySettings) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onScreenOpened: suspend () -> Unit = {}
) {
    val presets by presetsFlow.collectAsState(initial = emptyList())
    val displaySettings by displaySettingsFlow.collectAsState(initial = null)
    val display = displaySettings ?: PresetDisplaySettings()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<MacroPreset?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Top up search keywords for anything new. Slow and silent, off the search path.
    LaunchedEffect(Unit) { onScreenOpened() }

    val visiblePresets = remember(presets, query) { PresetSearch.filter(presets, query) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Macro Presets",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSortSheet = true }) {
                    Icon(Icons.Default.Sort, contentDescription = "Sorting options")
                }
                Spacer(modifier = Modifier.width(4.dp))
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Preset")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search — try \"salty snack\"") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (query.isBlank()) {
                sortDescription(display)
            } else {
                "${visiblePresets.size} of ${presets.size}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (visiblePresets.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Fastfood,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (presets.isEmpty()) "No presets yet" else "Nothing matched",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = if (presets.isEmpty()) {
                            "Create presets for quick macro logging"
                        } else {
                            "Try a different word, or clear the search"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            val manualMode = display.sortMode == PresetSortMode.MANUAL && query.isBlank()
            LazyColumn {
                itemsIndexed(visiblePresets, key = { _, preset -> preset.id }) { index, preset ->
                    PresetCard(
                        preset = preset,
                        showReorderControls = manualMode,
                        canMoveUp = index > 0,
                        canMoveDown = index < visiblePresets.size - 1,
                        onApply = { onApplyPreset(preset) },
                        onEdit = { editingPreset = preset },
                        onDelete = { onDeletePreset(preset) },
                        onMove = { delta ->
                            val reordered = visiblePresets.map { it.id }.toMutableList()
                            val target = index + delta
                            if (target in reordered.indices) {
                                val moved = reordered.removeAt(index)
                                reordered.add(target, moved)
                                onReorder(reordered)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    if (showSortSheet) {
        PresetSortDialog(
            settings = display,
            onDismiss = { showSortSheet = false },
            onSave = {
                onSaveDisplaySettings(it)
                showSortSheet = false
            }
        )
    }

    // Add/Edit Preset Dialog
    if (showAddDialog || editingPreset != null) {
        PresetDialog(
            preset = editingPreset,
            onDismiss = {
                showAddDialog = false
                editingPreset = null
            },
            onSave = { preset ->
                onSavePreset(preset)
                showAddDialog = false
                editingPreset = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetCard(
    preset: MacroPreset,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    showReorderControls: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMove: (Int) -> Unit = {}
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (preset.isAi) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Created by AI",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${preset.calories} cal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CaloriesColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MacroChip("P: ${preset.proteinG}g", ProteinColor)
                MacroChip("C: ${preset.carbsG}g", CarbsColor)
                MacroChip("F: ${preset.fatG}g", FatColor)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showReorderControls) {
                    IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
                Button(
                    onClick = onApply,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Apply")
                }
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    if (!showReorderControls) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit")
                    }
                }
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    // Delete Confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Preset") },
            text = { Text("Are you sure you want to delete \"${preset.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun sortDescription(settings: PresetDisplaySettings): String {
    val sort = when (settings.sortMode) {
        PresetSortMode.ALPHABETICAL -> "A–Z"
        PresetSortMode.MOST_USED_WEEK -> "Most used this week"
        PresetSortMode.RECENTLY_USED -> "Recently used"
        PresetSortMode.MANUAL -> "Custom order"
    }
    if (!settings.splitAiAndManual) return sort
    return "$sort · ${if (settings.aiOnTop) "AI first" else "Manual first"}"
}

@Composable
fun PresetSortDialog(
    settings: PresetDisplaySettings,
    onDismiss: () -> Unit,
    onSave: (PresetDisplaySettings) -> Unit
) {
    var sortMode by remember { mutableStateOf(settings.sortMode) }
    var split by remember { mutableStateOf(settings.splitAiAndManual) }
    var aiOnTop by remember { mutableStateOf(settings.aiOnTop) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort presets") },
        text = {
            Column {
                listOf(
                    PresetSortMode.ALPHABETICAL to "Alphabetical",
                    PresetSortMode.MOST_USED_WEEK to "Most used (last 7 days)",
                    PresetSortMode.RECENTLY_USED to "Most recently used",
                    PresetSortMode.MANUAL to "Custom order"
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sortMode == mode,
                            onClick = { sortMode = mode }
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = split, onCheckedChange = { split = it })
                    Text("Separate AI and manual presets", style = MaterialTheme.typography.bodyMedium)
                }
                if (split) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = aiOnTop, onCheckedChange = { aiOnTop = it })
                        Text("Show AI presets first", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (sortMode == PresetSortMode.MANUAL) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Use the arrows on each preset to set the order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    settings.copy(
                        sortMode = sortMode,
                        splitAiAndManual = split,
                        aiOnTop = aiOnTop
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MacroChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

@Composable
fun PresetDialog(
    preset: MacroPreset?,
    onDismiss: () -> Unit,
    onSave: (MacroPreset) -> Unit
) {
    var name by remember { mutableStateOf(preset?.name ?: "") }
    var protein by remember { mutableStateOf(preset?.proteinG?.toString() ?: "") }
    var carbs by remember { mutableStateOf(preset?.carbsG?.toString() ?: "") }
    var fat by remember { mutableStateOf(preset?.fatG?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preset == null) "New Preset" else "Edit Preset") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = protein,
                    onValueChange = { protein = it.filter { c -> c.isDigit() } },
                    label = { Text("Protein (g)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = carbs,
                    onValueChange = { carbs = it.filter { c -> c.isDigit() } },
                    label = { Text("Carbs (g)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fat,
                    onValueChange = { fat = it.filter { c -> c.isDigit() } },
                    label = { Text("Fat (g)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            MacroPreset(
                                id = preset?.id ?: 0,
                                name = name,
                                proteinG = protein.toIntOrNull() ?: 0,
                                carbsG = carbs.toIntOrNull() ?: 0,
                                fatG = fat.toIntOrNull() ?: 0
                            )
                        )
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
