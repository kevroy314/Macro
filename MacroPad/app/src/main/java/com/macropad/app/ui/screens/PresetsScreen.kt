package com.macropad.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.ui.theme.*
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    presetsFlow: Flow<List<MacroPreset>>,
    onSavePreset: (MacroPreset) -> Unit,
    onDeletePreset: (MacroPreset) -> Unit,
    onApplyPreset: (MacroPreset) -> Unit
) {
    val presets by presetsFlow.collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<MacroPreset?>(null) }

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
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Preset")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (presets.isEmpty()) {
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
                        text = "No presets yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "Create presets for quick macro logging",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn {
                items(presets) { preset ->
                    PresetCard(
                        preset = preset,
                        onApply = { onApplyPreset(preset) },
                        onEdit = { editingPreset = preset },
                        onDelete = { onDeletePreset(preset) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
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
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
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
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
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
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
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
