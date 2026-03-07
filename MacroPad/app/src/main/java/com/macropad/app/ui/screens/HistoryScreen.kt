package com.macropad.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroEntryGroup
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.ui.theme.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TimeRange(val days: Int, val label: String) {
    WEEK(7, "7 Days"),
    MONTH(30, "30 Days"),
    QUARTER(90, "90 Days")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    macrosFlow: Flow<List<DailyMacro>>,
    targetFlow: Flow<MacroTarget?>,
    getGroupedEntries: (String) -> Flow<List<MacroEntryGroup>>,
    onEditAnnotation: (String, String) -> Unit
) {
    val allMacros by macrosFlow.collectAsState(initial = emptyList())
    val target by targetFlow.collectAsState(initial = MacroTarget())

    var selectedRange by remember { mutableStateOf(TimeRange.WEEK) }
    var showAnnotationDialog by remember { mutableStateOf<DailyMacro?>(null) }
    var selectedDayForBurnup by remember { mutableStateOf<DailyMacro?>(null) }
    var showNotePopup by remember { mutableStateOf<DailyMacro?>(null) }

    val cutoffDate = LocalDate.now().minusDays(selectedRange.days.toLong())
    val filteredMacros = allMacros.filter {
        LocalDate.parse(it.date) >= cutoffDate
    }.sortedBy { it.date }

    // Get indices of macros with annotations for chart markers
    val macrosWithNotes = filteredMacros.mapIndexedNotNull { index, macro ->
        if (!macro.annotation.isNullOrBlank()) index to macro else null
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Time Range Selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { selectedRange = range },
                        label = { Text(range.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Chart with clickable annotations
        if (filteredMacros.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Macro Trends",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        SimpleLineChartWithAnnotations(
                            macros = filteredMacros,
                            macrosWithNotes = macrosWithNotes,
                            onNoteClick = { macro -> showNotePopup = macro },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Legend
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            LegendItem("Protein", ProteinColor)
                            LegendItem("Carbs", CarbsColor)
                            LegendItem("Fat", FatColor)
                            if (macrosWithNotes.isNotEmpty()) {
                                LegendItem("Note", Accent)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Burnup Chart Section (for selected day)
        selectedDayForBurnup?.let { selectedMacro ->
            item(key = "burnup_${selectedMacro.date}") {
                val groupedEntries by getGroupedEntries(selectedMacro.date).collectAsState(initial = emptyList())

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Daily Burnup - ${LocalDate.parse(selectedMacro.date).format(DateTimeFormatter.ofPattern("MMM d"))}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { selectedDayForBurnup = null }) {
                                Text("Close")
                            }
                        }

                        if (groupedEntries.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))

                            BurnupChart(
                                groups = groupedEntries,
                                target = target ?: MacroTarget(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Entry log - show up to 10 entries, scrollable if more
                            Text(
                                text = "Entries (${groupedEntries.size} groups)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                groupedEntries.forEach { group ->
                                    val time = Instant.ofEpochMilli(group.startTime)
                                        .atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("h:mm a"))
                                    Text(
                                        text = "$time: P+${group.totalProtein} C+${group.totalCarbs} F+${group.totalFat}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No entry data available for this day",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // History List Header
        item {
            Text(
                text = "Daily Log",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap a day to see burnup chart",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Daily log items
        items(filteredMacros.reversed(), key = { it.date }) { macro ->
            DayCard(
                macro = macro,
                target = target ?: MacroTarget(),
                isSelected = selectedDayForBurnup?.date == macro.date,
                onEditAnnotation = { showAnnotationDialog = macro },
                onSelectForBurnup = {
                    selectedDayForBurnup = if (selectedDayForBurnup?.date == macro.date) null else macro
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // Annotation Dialog
    showAnnotationDialog?.let { macro ->
        AnnotationDialog(
            currentAnnotation = macro.annotation ?: "",
            onDismiss = { showAnnotationDialog = null },
            onConfirm = { annotation ->
                onEditAnnotation(macro.date, annotation)
                showAnnotationDialog = null
            }
        )
    }

    // Note popup from chart
    showNotePopup?.let { macro ->
        AlertDialog(
            onDismissRequest = { showNotePopup = null },
            title = {
                Text(
                    LocalDate.parse(macro.date).format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
                )
            },
            text = {
                Text(macro.annotation ?: "")
            },
            confirmButton = {
                TextButton(onClick = { showNotePopup = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun SimpleLineChartWithAnnotations(
    macros: List<DailyMacro>,
    macrosWithNotes: List<Pair<Int, DailyMacro>>,
    onNoteClick: (DailyMacro) -> Unit,
    modifier: Modifier = Modifier
) {
    if (macros.isEmpty()) return

    val maxProtein = macros.maxOfOrNull { it.proteinG } ?: 1
    val maxCarbs = macros.maxOfOrNull { it.carbsG } ?: 1
    val maxFat = macros.maxOfOrNull { it.fatG } ?: 1
    val maxValue = maxOf(maxProtein, maxCarbs, maxFat).toFloat().coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 20f

        if (macros.size < 2) return@Canvas

        val stepX = (width - 2 * padding) / (macros.size - 1)

        // Draw protein line
        val proteinPath = Path()
        macros.forEachIndexed { index, macro ->
            val x = padding + index * stepX
            val y = height - padding - (macro.proteinG / maxValue) * (height - 2 * padding)
            if (index == 0) proteinPath.moveTo(x, y)
            else proteinPath.lineTo(x, y)
        }
        drawPath(proteinPath, ProteinColor, style = Stroke(width = 3f))

        // Draw carbs line
        val carbsPath = Path()
        macros.forEachIndexed { index, macro ->
            val x = padding + index * stepX
            val y = height - padding - (macro.carbsG / maxValue) * (height - 2 * padding)
            if (index == 0) carbsPath.moveTo(x, y)
            else carbsPath.lineTo(x, y)
        }
        drawPath(carbsPath, CarbsColor, style = Stroke(width = 3f))

        // Draw fat line
        val fatPath = Path()
        macros.forEachIndexed { index, macro ->
            val x = padding + index * stepX
            val y = height - padding - (macro.fatG / maxValue) * (height - 2 * padding)
            if (index == 0) fatPath.moveTo(x, y)
            else fatPath.lineTo(x, y)
        }
        drawPath(fatPath, FatColor, style = Stroke(width = 3f))

        // Draw annotation markers at top of chart
        macrosWithNotes.forEach { (index, _) ->
            val x = padding + index * stepX
            drawCircle(
                color = Accent,
                radius = 8f,
                center = Offset(x, padding / 2 + 4f)
            )
        }
    }
}

@Composable
fun BurnupChart(
    groups: List<MacroEntryGroup>,
    target: MacroTarget,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 20f

        val maxProtein = maxOf(groups.last().runningProtein, target.proteinG)
        val maxCarbs = maxOf(groups.last().runningCarbs, target.carbsG)
        val maxFat = maxOf(groups.last().runningFat, target.fatG)
        val maxValue = maxOf(maxProtein, maxCarbs, maxFat).toFloat().coerceAtLeast(1f)

        val stepX = if (groups.size > 1) (width - 2 * padding) / groups.size else (width - 2 * padding)

        // Draw target lines (dashed)
        val targetY = { t: Int -> height - padding - (t / maxValue) * (height - 2 * padding) }

        // Protein target
        drawLine(
            color = ProteinColor.copy(alpha = 0.3f),
            start = Offset(padding, targetY(target.proteinG)),
            end = Offset(width - padding, targetY(target.proteinG)),
            strokeWidth = 2f
        )

        // Carbs target
        drawLine(
            color = CarbsColor.copy(alpha = 0.3f),
            start = Offset(padding, targetY(target.carbsG)),
            end = Offset(width - padding, targetY(target.carbsG)),
            strokeWidth = 2f
        )

        // Fat target
        drawLine(
            color = FatColor.copy(alpha = 0.3f),
            start = Offset(padding, targetY(target.fatG)),
            end = Offset(width - padding, targetY(target.fatG)),
            strokeWidth = 2f
        )

        // Draw burnup lines starting from 0
        val drawBurnup = { getValue: (MacroEntryGroup) -> Int, color: Color ->
            val path = Path()
            path.moveTo(padding, height - padding) // Start at 0
            groups.forEachIndexed { index, group ->
                val x = padding + (index + 1) * stepX
                val y = height - padding - (getValue(group) / maxValue) * (height - 2 * padding)
                path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3f))

            // Draw dots at each point
            groups.forEachIndexed { index, group ->
                val x = padding + (index + 1) * stepX
                val y = height - padding - (getValue(group) / maxValue) * (height - 2 * padding)
                drawCircle(color, 5f, Offset(x, y))
            }
        }

        drawBurnup({ it.runningProtein }, ProteinColor)
        drawBurnup({ it.runningCarbs }, CarbsColor)
        drawBurnup({ it.runningFat }, FatColor)
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(12.dp),
            color = color,
            shape = MaterialTheme.shapes.small
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayCard(
    macro: DailyMacro,
    target: MacroTarget,
    isSelected: Boolean,
    onEditAnnotation: () -> Unit,
    onSelectForBurnup: () -> Unit
) {
    val date = LocalDate.parse(macro.date)
    val isToday = date == LocalDate.now()

    // Check for exceeded targets
    val anyExceeded = macro.proteinG > target.proteinG ||
            macro.carbsG > target.carbsG ||
            macro.fatG > target.fatG ||
            macro.calories > target.caloriesTarget

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectForBurnup() },
        colors = when {
            isSelected -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            isToday -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else -> CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (anyExceeded) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Target exceeded",
                            tint = Red,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row {
                    if (isToday) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Today") }
                        )
                    }
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(4.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("Burnup") }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MacroStat("P", macro.proteinG, target.proteinG, ProteinColor)
                MacroStat("C", macro.carbsG, target.carbsG, CarbsColor)
                MacroStat("F", macro.fatG, target.fatG, FatColor)
                MacroStat("Cal", macro.calories, target.caloriesTarget, CaloriesColor)
            }

            // Annotation
            if (!macro.annotation.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.clickable { onEditAnnotation() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Note,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Accent
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = macro.annotation,
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent
                    )
                }
            }
        }
    }
}

@Composable
fun MacroStat(label: String, current: Int, target: Int, color: Color) {
    val isOver = current > target

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        Text(
            text = current.toString(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (isOver) Red else MaterialTheme.colorScheme.onSurface
        )
    }
}
