package com.macropad.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.macropad.app.MacroPadApplication
import com.macropad.app.data.entity.ConflictResolution
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.SyncSettings
import com.macropad.app.data.entity.WidgetSettings
import com.macropad.app.sync.BackupData
import com.macropad.app.sync.SyncResult
import com.macropad.app.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    targetFlow: Flow<MacroTarget?>,
    widgetSettingsFlow: Flow<WidgetSettings?>,
    syncSettingsFlow: Flow<SyncSettings?>,
    onSaveTarget: (MacroTarget) -> Unit,
    onSaveWidgetSettings: (WidgetSettings) -> Unit,
    onSaveSyncSettings: (SyncSettings) -> Unit,
    getAllMacros: suspend () -> List<DailyMacro>,
    getAllPresets: suspend () -> List<MacroPreset>,
    getTarget: suspend () -> MacroTarget,
    getWidgetSettings: suspend () -> WidgetSettings,
    createBackup: suspend () -> BackupData,
    importBackup: suspend (BackupData) -> Unit,
    isLocalDataEmpty: suspend () -> Boolean,
    onImportData: suspend (List<DailyMacro>, List<MacroPreset>, MacroTarget?, WidgetSettings?) -> Unit
) {
    val target by targetFlow.collectAsState(initial = MacroTarget())
    val widgetSettings by widgetSettingsFlow.collectAsState(initial = WidgetSettings())
    val syncSettings by syncSettingsFlow.collectAsState(initial = SyncSettings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as MacroPadApplication

    var showTargetDialog by remember { mutableStateOf(false) }
    var showWidgetDialog by remember { mutableStateOf(false) }
    var showSyncSettingsDialog by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf<BackupData?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var dropboxStatus by remember { mutableStateOf<String?>(null) }
    var isDropboxLinked by remember { mutableStateOf(app.dropboxManager.isLinked) }
    var dropboxEmail by remember { mutableStateOf(app.dropboxManager.accountEmail) }
    var lastSyncTime by remember { mutableStateOf(app.dropboxManager.lastSyncTime) }

    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Check Dropbox auth state when screen resumes (after returning from browser auth)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val wasLinked = isDropboxLinked
                val nowLinked = app.dropboxManager.isLinked

                if (!wasLinked && nowLinked) {
                    // Just connected
                    isDropboxLinked = true
                    dropboxEmail = app.dropboxManager.accountEmail
                    dropboxStatus = "Connected to Dropbox!"
                    lastSyncTime = app.dropboxManager.lastSyncTime

                    // Enable auto-sync by default when linking
                    val currentSync = syncSettings ?: SyncSettings()
                    onSaveSyncSettings(currentSync.copy(autoSyncEnabled = true))
                    SyncWorker.scheduleDailySync(context)
                } else if (wasLinked && !nowLinked) {
                    // Disconnected externally
                    isDropboxLinked = false
                    dropboxEmail = null
                } else {
                    // Update state in case email/sync time changed
                    isDropboxLinked = nowLinked
                    dropboxEmail = app.dropboxManager.accountEmail
                    lastSyncTime = app.dropboxManager.lastSyncTime
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    importStatus = "Importing..."
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    if (content != null) {
                        val result = parseImportJson(content)
                        onImportData(result.macros, result.presets, result.target, result.widgetSettings)
                        importStatus = "Import successful!"
                    } else {
                        importStatus = "Failed to read file"
                    }
                } catch (e: Exception) {
                    importStatus = "Import failed: ${e.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Dropbox Sync Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Dropbox Sync",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (isDropboxLinked) {
                        IconButton(onClick = { showSyncSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Sync Settings")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isDropboxLinked) {
                    // Connected state
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = dropboxEmail ?: "Connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (lastSyncTime > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                        Text(
                            text = "Last sync: ${dateFormat.format(Date(lastSyncTime))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    val currentSync = syncSettings ?: SyncSettings()
                    if (currentSync.autoSyncEnabled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Auto-sync: Daily",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            onClick = {
                                dropboxStatus = "Backing up..."
                                scope.launch {
                                    val backup = createBackup()
                                    when (val result = app.dropboxManager.uploadBackup(backup)) {
                                        is SyncResult.Success -> {
                                            dropboxStatus = "Backup complete!"
                                            lastSyncTime = app.dropboxManager.lastSyncTime
                                        }
                                        is SyncResult.Error -> {
                                            dropboxStatus = result.message
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Backup")
                        }

                        OutlinedButton(
                            onClick = {
                                dropboxStatus = "Downloading..."
                                scope.launch {
                                    when (val result = app.dropboxManager.downloadBackup()) {
                                        is SyncResult.SuccessWithData -> {
                                            val currentSyncSettings = syncSettings ?: SyncSettings()
                                            val isEmpty = isLocalDataEmpty()

                                            when {
                                                isEmpty -> {
                                                    // Local is empty - use remote
                                                    importBackup(result.backup)
                                                    dropboxStatus = "Restored from Dropbox!"
                                                }
                                                currentSyncSettings.conflictResolution == ConflictResolution.ASK_USER -> {
                                                    // Show conflict dialog
                                                    showConflictDialog = result.backup
                                                    dropboxStatus = null
                                                }
                                                currentSyncSettings.conflictResolution == ConflictResolution.REMOTE_WINS -> {
                                                    importBackup(result.backup)
                                                    dropboxStatus = "Restored from Dropbox!"
                                                }
                                                else -> {
                                                    // LOCAL_WINS - don't overwrite
                                                    dropboxStatus = "Local data kept (local wins)"
                                                }
                                            }
                                        }
                                        is SyncResult.NoRemoteData -> {
                                            dropboxStatus = "No backup found on Dropbox"
                                        }
                                        is SyncResult.Error -> {
                                            dropboxStatus = result.message
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Restore")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            app.dropboxManager.unlink()
                            SyncWorker.cancelSync(context)
                            isDropboxLinked = false
                            dropboxEmail = null
                            dropboxStatus = "Disconnected from Dropbox"
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // Not connected state
                    Text(
                        text = "Sync your data to Dropbox for backup and portability",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val intent = app.dropboxManager.startAuth()
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect Dropbox")
                    }
                }

                dropboxStatus?.let { status ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.contains("fail", ignoreCase = true) || status.contains("error", ignoreCase = true))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Daily Targets Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Daily Targets",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { showTargetDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Targets")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val currentTarget = target ?: MacroTarget()

                TargetRow("Protein", "${currentTarget.proteinG}g")
                TargetRow("Carbs", "${currentTarget.carbsG}g")
                TargetRow("Fat", "${currentTarget.fatG}g")
                Spacer(modifier = Modifier.height(8.dp))
                TargetRow("Calories", "${currentTarget.caloriesTarget}", bold = true)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Widget Increment Settings Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Widgets, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Widget Buttons",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { showWidgetDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Widget Settings")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val currentSettings = widgetSettings ?: WidgetSettings()

                Text(
                    text = "Increment (+)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("P: +${currentSettings.proteinIncrement}", style = MaterialTheme.typography.bodyMedium)
                    Text("C: +${currentSettings.carbsIncrement}", style = MaterialTheme.typography.bodyMedium)
                    Text("F: +${currentSettings.fatIncrement}", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Decrement (-)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("P: -${currentSettings.proteinDecrement}", style = MaterialTheme.typography.bodyMedium)
                    Text("C: -${currentSettings.carbsDecrement}", style = MaterialTheme.typography.bodyMedium)
                    Text("F: -${currentSettings.fatDecrement}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Day Reset Time Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Day Reset Time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { showWidgetDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Day Reset Time")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val resetSettings = widgetSettings ?: WidgetSettings()
                val resetHour = resetSettings.dayResetHour
                val displayTime = if (resetHour == 0) "Midnight (12:00 AM)" else {
                    val hour12 = if (resetHour > 12) resetHour - 12 else if (resetHour == 0) 12 else resetHour
                    val amPm = if (resetHour < 12) "AM" else "PM"
                    "$hour12:00 $amPm"
                }

                Text(
                    text = "New day starts at: $displayTime",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Tracking resets at this hour each day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Export Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Export Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = {
                            exportStatus = "Exporting..."
                            scope.launch {
                                try {
                                    val macros = getAllMacros()
                                    val csv = buildCsvExport(macros)
                                    shareFile(context, csv, "macropad_export.csv", "text/csv")
                                    exportStatus = "Export ready!"
                                } catch (e: Exception) {
                                    exportStatus = "Export failed: ${e.message}"
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CSV")
                    }

                    OutlinedButton(
                        onClick = {
                            exportStatus = "Exporting..."
                            scope.launch {
                                try {
                                    val macros = getAllMacros()
                                    val presets = getAllPresets()
                                    val targetData = getTarget()
                                    val widgetSettingsData = getWidgetSettings()
                                    val json = buildJsonExport(macros, presets, targetData, widgetSettingsData)
                                    shareFile(context, json, "macropad_backup.json", "application/json")
                                    exportStatus = "Export ready!"
                                } catch (e: Exception) {
                                    exportStatus = "Export failed: ${e.message}"
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("JSON")
                    }
                }

                exportStatus?.let { status ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.contains("failed")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Import Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Import Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Restore from a JSON backup file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { importLauncher.launch("application/json") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Backup File")
                }

                importStatus?.let { status ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.contains("failed")) MaterialTheme.colorScheme.error
                        else if (status.contains("successful")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "MacroPad v2.0",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Fast macro nutrient tracking with widgets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    // Target Dialog
    if (showTargetDialog) {
        TargetDialog(
            target = target ?: MacroTarget(),
            onDismiss = { showTargetDialog = false },
            onSave = { newTarget ->
                onSaveTarget(newTarget)
                showTargetDialog = false
            }
        )
    }

    // Widget Settings Dialog
    if (showWidgetDialog) {
        WidgetSettingsDialog(
            settings = widgetSettings ?: WidgetSettings(),
            onDismiss = { showWidgetDialog = false },
            onSave = { newSettings ->
                onSaveWidgetSettings(newSettings)
                showWidgetDialog = false
            }
        )
    }

    // Sync Settings Dialog
    if (showSyncSettingsDialog) {
        SyncSettingsDialog(
            settings = syncSettings ?: SyncSettings(),
            onDismiss = { showSyncSettingsDialog = false },
            onSave = { newSettings ->
                onSaveSyncSettings(newSettings)
                if (newSettings.autoSyncEnabled) {
                    SyncWorker.scheduleDailySync(context)
                } else {
                    SyncWorker.cancelSync(context)
                }
                showSyncSettingsDialog = false
            }
        )
    }

    // Conflict Resolution Dialog
    showConflictDialog?.let { remoteBackup ->
        AlertDialog(
            onDismissRequest = { showConflictDialog = null },
            title = { Text("Sync Conflict") },
            text = {
                Column {
                    Text("Both local and Dropbox have data. Which would you like to keep?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Remote: ${remoteBackup.dailyMacros.size} days, ${remoteBackup.presets.size} presets",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            importBackup(remoteBackup)
                            dropboxStatus = "Restored from Dropbox!"
                        }
                        showConflictDialog = null
                    }
                ) {
                    Text("Use Dropbox")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dropboxStatus = "Local data kept"
                        showConflictDialog = null
                    }
                ) {
                    Text("Keep Local")
                }
            }
        )
    }
}

@Composable
fun SyncSettingsDialog(
    settings: SyncSettings,
    onDismiss: () -> Unit,
    onSave: (SyncSettings) -> Unit
) {
    var autoSync by remember { mutableStateOf(settings.autoSyncEnabled) }
    var conflictResolution by remember { mutableStateOf(settings.conflictResolution) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Settings") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-sync daily")
                    Switch(
                        checked = autoSync,
                        onCheckedChange = { autoSync = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Conflict Resolution",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column {
                    ConflictResolution.entries.forEach { resolution ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = conflictResolution == resolution,
                                onClick = { conflictResolution = resolution }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = when (resolution) {
                                        ConflictResolution.LOCAL_WINS -> "Local wins"
                                        ConflictResolution.REMOTE_WINS -> "Dropbox wins"
                                        ConflictResolution.ASK_USER -> "Ask me"
                                    }
                                )
                                Text(
                                    text = when (resolution) {
                                        ConflictResolution.LOCAL_WINS -> "Keep local data, upload to Dropbox"
                                        ConflictResolution.REMOTE_WINS -> "Replace local with Dropbox data"
                                        ConflictResolution.ASK_USER -> "Prompt when conflicts occur"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        settings.copy(
                            autoSyncEnabled = autoSync,
                            conflictResolution = conflictResolution
                        )
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
fun TargetRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun TargetDialog(
    target: MacroTarget,
    onDismiss: () -> Unit,
    onSave: (MacroTarget) -> Unit
) {
    var protein by remember { mutableStateOf(target.proteinG.toString()) }
    var carbs by remember { mutableStateOf(target.carbsG.toString()) }
    var fat by remember { mutableStateOf(target.fatG.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily Targets") },
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

                val calories = (protein.toIntOrNull() ?: 0) * 4 +
                        (carbs.toIntOrNull() ?: 0) * 4 +
                        (fat.toIntOrNull() ?: 0) * 9

                Text(
                    text = "Target Calories: $calories",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        MacroTarget(
                            proteinG = protein.toIntOrNull() ?: 180,
                            carbsG = carbs.toIntOrNull() ?: 250,
                            fatG = fat.toIntOrNull() ?: 70
                        )
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
fun WidgetSettingsDialog(
    settings: WidgetSettings,
    onDismiss: () -> Unit,
    onSave: (WidgetSettings) -> Unit
) {
    var proteinInc by remember { mutableStateOf(settings.proteinIncrement.toString()) }
    var carbsInc by remember { mutableStateOf(settings.carbsIncrement.toString()) }
    var fatInc by remember { mutableStateOf(settings.fatIncrement.toString()) }
    var proteinDec by remember { mutableStateOf(settings.proteinDecrement.toString()) }
    var carbsDec by remember { mutableStateOf(settings.carbsDecrement.toString()) }
    var fatDec by remember { mutableStateOf(settings.fatDecrement.toString()) }
    var dayResetHour by remember { mutableStateOf(settings.dayResetHour.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Settings") },
        text = {
            Column {
                // Day Reset Hour
                Text(
                    text = "Day Reset Time",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Hour when the day resets (0-23). E.g., 5 = 5am",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dayResetHour,
                    onValueChange = {
                        val filtered = it.filter { c -> c.isDigit() }
                        val value = filtered.toIntOrNull() ?: 0
                        if (value in 0..23) dayResetHour = filtered
                    },
                    label = { Text("Hour (0-23)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Increment (+) Values",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = proteinInc,
                        onValueChange = { proteinInc = it.filter { c -> c.isDigit() } },
                        label = { Text("P") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = carbsInc,
                        onValueChange = { carbsInc = it.filter { c -> c.isDigit() } },
                        label = { Text("C") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fatInc,
                        onValueChange = { fatInc = it.filter { c -> c.isDigit() } },
                        label = { Text("F") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Decrement (-) Values",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = proteinDec,
                        onValueChange = { proteinDec = it.filter { c -> c.isDigit() } },
                        label = { Text("P") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = carbsDec,
                        onValueChange = { carbsDec = it.filter { c -> c.isDigit() } },
                        label = { Text("C") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fatDec,
                        onValueChange = { fatDec = it.filter { c -> c.isDigit() } },
                        label = { Text("F") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        WidgetSettings(
                            proteinIncrement = proteinInc.toIntOrNull() ?: 5,
                            carbsIncrement = carbsInc.toIntOrNull() ?: 5,
                            fatIncrement = fatInc.toIntOrNull() ?: 5,
                            proteinDecrement = proteinDec.toIntOrNull() ?: 1,
                            carbsDecrement = carbsDec.toIntOrNull() ?: 1,
                            fatDecrement = fatDec.toIntOrNull() ?: 1,
                            dayResetHour = dayResetHour.toIntOrNull()?.coerceIn(0, 23) ?: 0
                        )
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

private fun buildCsvExport(macros: List<DailyMacro>): String {
    val sb = StringBuilder()
    sb.appendLine("date,protein,carbs,fat,calories,annotation")
    macros.sortedBy { it.date }.forEach { macro ->
        val annotation = macro.annotation?.replace(",", ";")?.replace("\n", " ") ?: ""
        sb.appendLine("${macro.date},${macro.proteinG},${macro.carbsG},${macro.fatG},${macro.calories},\"$annotation\"")
    }
    return sb.toString()
}

private fun buildJsonExport(
    macros: List<DailyMacro>,
    presets: List<MacroPreset>,
    target: MacroTarget,
    widgetSettings: WidgetSettings
): String {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val export = mapOf(
        "version" to 2,
        "exportDate" to java.time.LocalDateTime.now().toString(),
        "targets" to mapOf(
            "protein" to target.proteinG,
            "carbs" to target.carbsG,
            "fat" to target.fatG
        ),
        "widgetSettings" to mapOf(
            "proteinIncrement" to widgetSettings.proteinIncrement,
            "carbsIncrement" to widgetSettings.carbsIncrement,
            "fatIncrement" to widgetSettings.fatIncrement,
            "proteinDecrement" to widgetSettings.proteinDecrement,
            "carbsDecrement" to widgetSettings.carbsDecrement,
            "fatDecrement" to widgetSettings.fatDecrement,
            "dayResetHour" to widgetSettings.dayResetHour
        ),
        "presets" to presets.map { preset ->
            mapOf(
                "name" to preset.name,
                "protein" to preset.proteinG,
                "carbs" to preset.carbsG,
                "fat" to preset.fatG
            )
        },
        "dailyMacros" to macros.sortedBy { it.date }.map { macro ->
            mapOf(
                "date" to macro.date,
                "protein" to macro.proteinG,
                "carbs" to macro.carbsG,
                "fat" to macro.fatG,
                "calories" to macro.calories,
                "annotation" to macro.annotation
            )
        }
    )
    return gson.toJson(export)
}

data class ImportResult(
    val macros: List<DailyMacro>,
    val presets: List<MacroPreset>,
    val target: MacroTarget?,
    val widgetSettings: WidgetSettings?
)

private fun parseImportJson(json: String): ImportResult {
    val gson = GsonBuilder().create()
    val mapType = object : TypeToken<Map<String, Any>>() {}.type
    val data: Map<String, Any> = gson.fromJson(json, mapType)

    // Parse targets
    val targets = data["targets"] as? Map<*, *>
    val target = if (targets != null) {
        MacroTarget(
            proteinG = (targets["protein"] as? Double)?.toInt() ?: 180,
            carbsG = (targets["carbs"] as? Double)?.toInt() ?: 250,
            fatG = (targets["fat"] as? Double)?.toInt() ?: 70
        )
    } else null

    // Parse widget settings
    val widgetSettingsMap = data["widgetSettings"] as? Map<*, *>
    val widgetSettings = if (widgetSettingsMap != null) {
        WidgetSettings(
            proteinIncrement = (widgetSettingsMap["proteinIncrement"] as? Double)?.toInt() ?: 5,
            carbsIncrement = (widgetSettingsMap["carbsIncrement"] as? Double)?.toInt() ?: 5,
            fatIncrement = (widgetSettingsMap["fatIncrement"] as? Double)?.toInt() ?: 5,
            proteinDecrement = (widgetSettingsMap["proteinDecrement"] as? Double)?.toInt() ?: 1,
            carbsDecrement = (widgetSettingsMap["carbsDecrement"] as? Double)?.toInt() ?: 1,
            fatDecrement = (widgetSettingsMap["fatDecrement"] as? Double)?.toInt() ?: 1,
            dayResetHour = (widgetSettingsMap["dayResetHour"] as? Double)?.toInt()?.coerceIn(0, 23) ?: 0
        )
    } else null

    // Parse presets
    val presetsList = data["presets"] as? List<*> ?: emptyList<Any>()
    val presets = presetsList.mapNotNull { item ->
        val preset = item as? Map<*, *> ?: return@mapNotNull null
        MacroPreset(
            name = preset["name"] as? String ?: "",
            proteinG = (preset["protein"] as? Double)?.toInt() ?: 0,
            carbsG = (preset["carbs"] as? Double)?.toInt() ?: 0,
            fatG = (preset["fat"] as? Double)?.toInt() ?: 0
        )
    }

    // Parse daily macros
    val macrosList = data["dailyMacros"] as? List<*> ?: emptyList<Any>()
    val macros = macrosList.mapNotNull { item ->
        val macro = item as? Map<*, *> ?: return@mapNotNull null
        DailyMacro(
            date = macro["date"] as? String ?: return@mapNotNull null,
            proteinG = (macro["protein"] as? Double)?.toInt() ?: 0,
            carbsG = (macro["carbs"] as? Double)?.toInt() ?: 0,
            fatG = (macro["fat"] as? Double)?.toInt() ?: 0,
            annotation = macro["annotation"] as? String
        )
    }

    return ImportResult(macros, presets, target, widgetSettings)
}

private fun shareFile(context: Context, content: String, filename: String, mimeType: String) {
    val file = File(context.cacheDir, filename)
    file.writeText(content)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(intent, "Export MacroPad Data"))
}
