package com.macropad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.updateAll
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.SyncSettings
import com.macropad.app.data.entity.WidgetSettings
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.sync.DropboxManager
import com.macropad.app.ui.screens.*
import com.macropad.app.ui.theme.MacroPadTheme
import com.macropad.app.ui.widgets.IncrementWidget
import com.macropad.app.ui.widgets.MacroStatusWidget
import com.macropad.app.ui.widgets.PresetWidget
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: MacroRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = (application as MacroPadApplication).repository

        setContent {
            MacroPadTheme {
                MainScreen(repository)
            }
        }
    }

    private fun updateWidgets() {
        kotlinx.coroutines.GlobalScope.launch {
            MacroStatusWidget().updateAll(this@MainActivity)
            IncrementWidget().updateAll(this@MainActivity)
            PresetWidget().updateAll(this@MainActivity)
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object Dashboard : Screen("dashboard", "Today", { Icon(Icons.Default.Home, contentDescription = null) })
    object History : Screen("history", "History", { Icon(Icons.Default.Timeline, contentDescription = null) })
    object Presets : Screen("presets", "Presets", { Icon(Icons.Default.Fastfood, contentDescription = null) })
    object Settings : Screen("settings", "Settings", { Icon(Icons.Default.Settings, contentDescription = null) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: MacroRepository) {
    val navController = rememberNavController()
    val screens = listOf(Screen.Dashboard, Screen.History, Screen.Presets, Screen.Settings)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun updateWidgets() {
        scope.launch {
            MacroStatusWidget().updateAll(context)
            IncrementWidget().updateAll(context)
            PresetWidget().updateAll(context)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = screen.icon,
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    todayMacrosFlow = repository.getTodayMacrosFlow(),
                    targetFlow = repository.getTargetFlow(),
                    presetsFlow = repository.getAllPresetsFlow(),
                    widgetSettingsFlow = repository.getWidgetSettingsFlow(),
                    onAddMacros = { protein, carbs, fat ->
                        scope.launch {
                            repository.addMacros(protein, carbs, fat)
                            updateWidgets()
                        }
                    },
                    onSetMacros = { protein, carbs, fat ->
                        scope.launch {
                            repository.setMacros(protein, carbs, fat)
                            updateWidgets()
                        }
                    },
                    onApplyPreset = { preset ->
                        scope.launch {
                            repository.applyPreset(preset)
                            updateWidgets()
                        }
                    },
                    onEditAnnotation = { annotation ->
                        scope.launch {
                            repository.updateAnnotation(repository.getAdjustedTodayDate(), annotation)
                        }
                    },
                    onUndo = {
                        val undone = repository.undoLastEntry()
                        if (undone != null) {
                            updateWidgets()
                        }
                        undone
                    }
                )
            }

            composable(Screen.History.route) {
                HistoryScreen(
                    macrosFlow = repository.getAllMacrosFlow(),
                    targetFlow = repository.getTargetFlow(),
                    getGroupedEntries = { date -> repository.getGroupedEntriesFlow(date) },
                    onEditAnnotation = { date, annotation ->
                        scope.launch {
                            repository.updateAnnotation(date, annotation)
                        }
                    }
                )
            }

            composable(Screen.Presets.route) {
                PresetsScreen(
                    presetsFlow = repository.getAllPresetsFlow(),
                    onSavePreset = { preset ->
                        scope.launch {
                            repository.savePreset(preset)
                            updateWidgets()
                        }
                    },
                    onDeletePreset = { preset ->
                        scope.launch {
                            repository.deletePreset(preset)
                            updateWidgets()
                        }
                    },
                    onApplyPreset = { preset ->
                        scope.launch {
                            repository.applyPreset(preset)
                            updateWidgets()
                        }
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    targetFlow = repository.getTargetFlow(),
                    widgetSettingsFlow = repository.getWidgetSettingsFlow(),
                    syncSettingsFlow = repository.getSyncSettingsFlow(),
                    onSaveTarget = { target ->
                        scope.launch {
                            repository.saveTarget(target)
                            updateWidgets()
                        }
                    },
                    onSaveWidgetSettings = { settings ->
                        scope.launch {
                            repository.saveWidgetSettings(settings)
                            updateWidgets()
                        }
                    },
                    onSaveSyncSettings = { syncSettings ->
                        scope.launch {
                            repository.saveSyncSettings(syncSettings)
                        }
                    },
                    getAllMacros = { repository.getAllMacros() },
                    getAllPresets = { repository.getAllPresets() },
                    getTarget = { repository.getTarget() },
                    getWidgetSettings = { repository.getWidgetSettings() },
                    createBackup = { repository.createBackup() },
                    importBackup = { backup ->
                        repository.importBackup(backup)
                        updateWidgets()
                    },
                    isLocalDataEmpty = { repository.isLocalDataEmpty() },
                    onImportData = { macros, presets, target, widgetSettings ->
                        repository.importMacros(macros)
                        repository.importPresets(presets)
                        target?.let { repository.saveTarget(it) }
                        widgetSettings?.let { repository.saveWidgetSettings(it) }
                        updateWidgets()
                    }
                )
            }
        }
    }
}
