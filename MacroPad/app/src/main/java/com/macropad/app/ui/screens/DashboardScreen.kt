package com.macropad.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntry
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.WidgetSettings
import kotlinx.coroutines.launch
import com.macropad.app.ui.components.MacroProgressBar
import com.macropad.app.ui.theme.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    todayMacrosFlow: Flow<DailyMacro?>,
    targetFlow: Flow<MacroTarget?>,
    presetsFlow: Flow<List<MacroPreset>>,
    widgetSettingsFlow: Flow<WidgetSettings?>,
    onAddMacros: (protein: Int, carbs: Int, fat: Int) -> Unit,
    onSetMacros: (protein: Int, carbs: Int, fat: Int) -> Unit,
    onApplyPreset: (MacroPreset) -> Unit,
    onEditAnnotation: (String) -> Unit,
    onUndo: suspend () -> MacroEntry?
) {
    val todayMacros by todayMacrosFlow.collectAsState(initial = null)
    val target by targetFlow.collectAsState(initial = MacroTarget())
    val presets by presetsFlow.collectAsState(initial = emptyList())
    val widgetSettings by widgetSettingsFlow.collectAsState(initial = WidgetSettings())

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showAnnotationDialog by remember { mutableStateOf(false) }
    var undoMessage by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val currentTarget = target ?: MacroTarget()
    val macros = todayMacros ?: DailyMacro(date = DailyMacro.today())
    val settings = widgetSettings ?: WidgetSettings()

    // Check for exceeded targets
    val proteinExceeded = macros.proteinG > currentTarget.proteinG
    val carbsExceeded = macros.carbsG > currentTarget.carbsG
    val fatExceeded = macros.fatG > currentTarget.fatG
    val caloriesExceeded = macros.calories > currentTarget.caloriesTarget

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Date Header with alert if any target exceeded
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (proteinExceeded || carbsExceeded || fatExceeded || caloriesExceeded) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Target exceeded",
                    tint = Red,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Annotation
        val annotation = todayMacros?.annotation
        if (!annotation.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Note, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = annotation, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Macro Progress Cards
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Today's Macros",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                MacroProgressBar(
                    label = "Protein",
                    current = macros.proteinG,
                    target = currentTarget.proteinG,
                    color = ProteinColor,
                    exceeded = proteinExceeded
                )

                Spacer(modifier = Modifier.height(12.dp))

                MacroProgressBar(
                    label = "Carbs",
                    current = macros.carbsG,
                    target = currentTarget.carbsG,
                    color = CarbsColor,
                    exceeded = carbsExceeded
                )

                Spacer(modifier = Modifier.height(12.dp))

                MacroProgressBar(
                    label = "Fat",
                    current = macros.fatG,
                    target = currentTarget.fatG,
                    color = FatColor,
                    exceeded = fatExceeded
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Calories
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Calories",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (caloriesExceeded) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Exceeded",
                                tint = Red,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = "${macros.calories} / ${currentTarget.caloriesTarget}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (caloriesExceeded) Red else CaloriesColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons (Add/Edit/Note/Undo)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add")
            }
            OutlinedButton(onClick = { showEditDialog = true }) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit")
            }
            OutlinedButton(onClick = { showAnnotationDialog = true }) {
                Icon(Icons.Default.Note, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Note")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val undone = onUndo()
                        undoMessage = if (undone != null) {
                            "Undid: P${undone.proteinG} C${undone.carbsG} F${undone.fatG}"
                        } else {
                            "Nothing to undo"
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
            }
        }

        // Undo feedback message
        undoMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = { undoMessage = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Add - Widget Style (+/- per macro)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Quick Add",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Protein column
                    QuickAddColumn(
                        label = "Protein",
                        shortLabel = "P",
                        increment = settings.proteinIncrement,
                        decrement = settings.proteinDecrement,
                        color = ProteinColor,
                        onIncrement = { onAddMacros(settings.proteinIncrement, 0, 0) },
                        onDecrement = { onAddMacros(-settings.proteinDecrement, 0, 0) }
                    )

                    // Carbs column
                    QuickAddColumn(
                        label = "Carbs",
                        shortLabel = "C",
                        increment = settings.carbsIncrement,
                        decrement = settings.carbsDecrement,
                        color = CarbsColor,
                        onIncrement = { onAddMacros(0, settings.carbsIncrement, 0) },
                        onDecrement = { onAddMacros(0, -settings.carbsDecrement, 0) }
                    )

                    // Fat column
                    QuickAddColumn(
                        label = "Fat",
                        shortLabel = "F",
                        increment = settings.fatIncrement,
                        decrement = settings.fatDecrement,
                        color = FatColor,
                        onIncrement = { onAddMacros(0, 0, settings.fatIncrement) },
                        onDecrement = { onAddMacros(0, 0, -settings.fatDecrement) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Presets
        if (presets.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Presets",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    presets.take(5).forEach { preset ->
                        OutlinedButton(
                            onClick = { onApplyPreset(preset) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(preset.name)
                                Text(
                                    text = "P:${preset.proteinG} C:${preset.carbsG} F:${preset.fatG}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Macros Dialog
    if (showAddDialog) {
        AddMacrosDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { p, c, f ->
                onAddMacros(p, c, f)
                showAddDialog = false
            }
        )
    }

    // Edit Macros Dialog
    if (showEditDialog) {
        EditMacrosDialog(
            currentProtein = macros.proteinG,
            currentCarbs = macros.carbsG,
            currentFat = macros.fatG,
            onDismiss = { showEditDialog = false },
            onConfirm = { p, c, f ->
                onSetMacros(p, c, f)
                showEditDialog = false
            }
        )
    }

    // Annotation Dialog
    if (showAnnotationDialog) {
        AnnotationDialog(
            currentAnnotation = todayMacros?.annotation ?: "",
            onDismiss = { showAnnotationDialog = false },
            onConfirm = { annotation ->
                onEditAnnotation(annotation)
                showAnnotationDialog = false
            }
        )
    }
}

@Composable
fun QuickAddColumn(
    label: String,
    shortLabel: String,
    increment: Int,
    decrement: Int,
    color: androidx.compose.ui.graphics.Color,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    val dimColor = color.copy(alpha = 0.4f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label
        Text(
            text = shortLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // + button with long-press repeat
        RepeatingButton(
            onClick = onIncrement,
            modifier = Modifier
                .size(56.dp, 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
        ) {
            Text(
                text = "+$increment",
                color = MaterialTheme.colorScheme.surface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // - button with long-press repeat
        RepeatingButton(
            onClick = onDecrement,
            modifier = Modifier
                .size(56.dp, 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(dimColor)
        ) {
            Text(
                text = "-$decrement",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun RepeatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialDelayMs: Long = 500L,
    repeatDelayMs: Long = 100L,
    content: @Composable BoxScope.() -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            onClick() // Initial click
            delay(initialDelayMs) // Wait before repeating
            while (isPressed) {
                onClick()
                delay(repeatDelayMs)
            }
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun AddMacrosDialog(
    onDismiss: () -> Unit,
    onConfirm: (protein: Int, carbs: Int, fat: Int) -> Unit
) {
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Macros") },
        text = {
            Column {
                OutlinedTextField(
                    value = protein,
                    onValueChange = { protein = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("Protein (g)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = carbs,
                    onValueChange = { carbs = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("Carbs (g)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fat,
                    onValueChange = { fat = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("Fat (g)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        protein.toIntOrNull() ?: 0,
                        carbs.toIntOrNull() ?: 0,
                        fat.toIntOrNull() ?: 0
                    )
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditMacrosDialog(
    currentProtein: Int,
    currentCarbs: Int,
    currentFat: Int,
    onDismiss: () -> Unit,
    onConfirm: (protein: Int, carbs: Int, fat: Int) -> Unit
) {
    var protein by remember { mutableStateOf(currentProtein.toString()) }
    var carbs by remember { mutableStateOf(currentCarbs.toString()) }
    var fat by remember { mutableStateOf(currentFat.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Macros") },
        text = {
            Column {
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

                Spacer(modifier = Modifier.height(12.dp))

                // Show calculated calories
                val calories = (protein.toIntOrNull() ?: 0) * 4 +
                        (carbs.toIntOrNull() ?: 0) * 4 +
                        (fat.toIntOrNull() ?: 0) * 9
                Text(
                    text = "Calories: $calories",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = CaloriesColor
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        protein.toIntOrNull() ?: 0,
                        carbs.toIntOrNull() ?: 0,
                        fat.toIntOrNull() ?: 0
                    )
                }
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

@Composable
fun AnnotationDialog(
    currentAnnotation: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var annotation by remember { mutableStateOf(currentAnnotation) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day Note") },
        text = {
            OutlinedTextField(
                value = annotation,
                onValueChange = { annotation = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(annotation) }) {
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
