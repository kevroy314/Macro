package com.macropad.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.glance.appwidget.updateAll
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.macropad.app.ai.AiJobSyncService
import com.macropad.app.ai.AiPollWorker
import com.macropad.app.ai.AiSyncManager
import com.macropad.app.ai.PlanningManager
import com.macropad.app.ai.PresetTagger
import com.macropad.app.ai.SetupLink
import com.macropad.app.data.entity.AiJob
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.PresetDisplaySettings
import com.macropad.app.data.entity.SyncSettings
import com.macropad.app.data.entity.WidgetSettings
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.net.AiCallResult
import com.macropad.app.net.AiClient
import com.macropad.app.ui.screens.*
import com.macropad.app.ui.theme.MacroPadTheme
import com.macropad.app.ui.widgets.IncrementWidget
import com.macropad.app.ui.widgets.MacroStatusWidget
import com.macropad.app.ui.widgets.PresetWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: MacroRepository
    private lateinit var aiSyncManager: AiSyncManager
    private lateinit var planningManager: PlanningManager
    private lateinit var presetTagger: PresetTagger

    /** Route requested by a notification tap or the AI Add widget. */
    private val pendingRoute = MutableStateFlow<String?>(null)

    /** A macropad://setup link, from a QR scanned by something other than us. */
    private val pendingSetup = MutableStateFlow<SetupLink.Setup?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Let insets reach Compose. Without this the decor view consumes them first,
        // WindowInsets.ime reads zero, and Modifier.imePadding() silently does
        // nothing — which is why the keyboard sat on top of the chat composer.
        // Scaffold pads its content by the system bars, so nothing slides under the
        // status bar as a result.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val app = application as MacroPadApplication
        repository = app.repository
        aiSyncManager = app.aiSyncManager
        planningManager = app.planningManager
        presetTagger = app.presetTagger

        // An estimate landing while the app is open should move the widgets too.
        aiSyncManager.onDataChanged = { updateWidgetsNow() }
        planningManager.onDataChanged = { updateWidgetsNow() }

        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        pendingSetup.value = SetupLink.decode(intent?.dataString)

        setContent {
            MacroPadTheme {
                MainScreen(
                    repository,
                    aiSyncManager,
                    planningManager,
                    presetTagger,
                    pendingRoute,
                    pendingSetup
                )
            }
        }

        // Catch anything that finished while the app was closed — but only bother
        // waking up periodically if the estimator is actually set up.
        kotlinx.coroutines.GlobalScope.launch {
            if (repository.getAiSettings().isConfigured) {
                AiPollWorker.ensureScheduled(this@MainActivity)
                if (repository.getWatchedAiJobs().isNotEmpty()) {
                    AiJobSyncService.start(this@MainActivity)
                }
            } else {
                AiPollWorker.cancel(this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        SetupLink.decode(intent.dataString)?.let { pendingSetup.value = it }
    }

    override fun onPause() {
        super.onPause()
        // Update widgets when leaving the app to ensure they reflect any changes made
        updateWidgets()
    }

    private fun updateWidgets() {
        kotlinx.coroutines.GlobalScope.launch { updateWidgetsNow() }
    }

    private suspend fun updateWidgetsNow() {
        MacroStatusWidget.forceUpdateAll(this@MainActivity)
        IncrementWidget().updateAll(this@MainActivity)
        PresetWidget.forceUpdateAll(this@MainActivity)
    }

    companion object {
        const val EXTRA_ROUTE = "macropad_route"
        const val ROUTE_AI_LOG = "ai_log"
        const val ROUTE_AI_ENTRY = "ai_entry"
        const val ROUTE_PLANNING = "planning"

        /** Placeholder id for a thread that hasn't been created on the server yet. */
        const val NEW_THREAD = "new"
    }
}

sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object Dashboard : Screen("dashboard", "Today", { Icon(Icons.Default.Home, contentDescription = null) })
    object History : Screen("history", "History", { Icon(Icons.Default.Timeline, contentDescription = null) })
    object Presets : Screen("presets", "Presets", { Icon(Icons.Default.Fastfood, contentDescription = null) })
    object AiLog : Screen(MainActivity.ROUTE_AI_LOG, "AI", { Icon(Icons.Default.AutoAwesome, contentDescription = null) })
    object Settings : Screen("settings", "Settings", { Icon(Icons.Default.Settings, contentDescription = null) })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    repository: MacroRepository,
    aiSyncManager: AiSyncManager,
    planningManager: PlanningManager,
    presetTagger: PresetTagger,
    pendingRoute: MutableStateFlow<String?>,
    pendingSetup: MutableStateFlow<SetupLink.Setup?>
) {
    val navController = rememberNavController()
    val screens = listOf(
        Screen.Dashboard,
        Screen.History,
        Screen.Presets,
        Screen.AiLog,
        Screen.Settings
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val incomingSetup by pendingSetup.collectAsState()
    incomingSetup?.let { setup ->
        AlertDialog(
            onDismissRequest = { pendingSetup.value = null },
            title = { Text("Use this AI server?") },
            text = {
                Column {
                    Text(setup.url, style = MaterialTheme.typography.bodyMedium)
                    if (setup.pin.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Includes this server's certificate, so the connection is " +
                                "encrypted without needing a public web address.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This replaces the AI server settings on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val current = repository.getAiSettings()
                        repository.saveAiSettings(
                            current.copy(
                                serverUrl = setup.url,
                                apiKey = setup.key,
                                // Always taken from the code, including when it is
                                // blank: scanning a public-hostname server must clear a
                                // pin left over from a self-signed one.
                                certPin = setup.pin,
                                enabled = true
                            )
                        )
                    }
                    pendingSetup.value = null
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSetup.value = null }) { Text("Cancel") }
            }
        )
    }

    // Notification taps and the AI Add widget both arrive as a requested route.
    val requestedRoute by pendingRoute.collectAsState()
    LaunchedEffect(requestedRoute) {
        requestedRoute?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            pendingRoute.value = null
        }
    }

    fun updateWidgets() {
        scope.launch {
            // Use forceUpdateAll for MacroStatusWidget and PresetWidget to ensure state change triggers re-render
            MacroStatusWidget.forceUpdateAll(context)
            IncrementWidget().updateAll(context)
            PresetWidget.forceUpdateAll(context)
        }
    }

    Scaffold(
        bottomBar = {
            // The AI entry form and a planning conversation are both full-screen
            // tasks. Hiding the bar also keeps it from sitting on top of the
            // keyboard and squeezing the chat.
            val fullScreenRoute = currentRoute == MainActivity.ROUTE_AI_ENTRY ||
                currentRoute?.startsWith(MainActivity.ROUTE_PLANNING) == true
            if (!fullScreenRoute) {
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
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            // consumeWindowInsets so a screen's own imePadding() adds only what the
            // Scaffold hasn't already applied, instead of stacking a second nav-bar
            // gap on top of the keyboard.
            modifier = Modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    todayMacrosFlow = repository.getTodayMacrosFlow(),
                    targetFlow = repository.getTargetFlow(),
                    presetsFlow = repository.getSortedPresetsFlow(),
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
                    },
                    onAiEntry = {
                        navController.navigate(MainActivity.ROUTE_AI_ENTRY) {
                            launchSingleTop = true
                        }
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
                    presetsFlow = repository.getSortedPresetsFlow(),
                    displaySettingsFlow = repository.getPresetDisplaySettingsFlow(),
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
                    },
                    onSaveDisplaySettings = { settings ->
                        scope.launch {
                            repository.savePresetDisplaySettings(settings)
                            updateWidgets()
                        }
                    },
                    onReorder = { ids ->
                        scope.launch {
                            repository.savePresetOrder(ids)
                            updateWidgets()
                        }
                    },
                    onScreenOpened = { presetTagger.refreshIfNeeded() }
                )
            }

            composable(Screen.AiLog.route) {
                AiHomeScreen(
                    threadsFlow = repository.getThreadsFlow(),
                    onOpenThread = { clientThreadId ->
                        navController.navigate("${MainActivity.ROUTE_PLANNING}/$clientThreadId") {
                            launchSingleTop = true
                        }
                    },
                    onNewThread = {
                        navController.navigate(
                            "${MainActivity.ROUTE_PLANNING}/${MainActivity.NEW_THREAD}"
                        ) { launchSingleTop = true }
                    },
                    onDeleteThread = { clientThreadId ->
                        planningManager.deleteThread(clientThreadId)
                    }
                ) {
                AiJobsScreen(
                    jobsFlow = repository.getAiJobsFlow(),
                    parseResult = { job -> repository.parseAiResult(job) },
                    imagePathsOf = { job -> aiSyncManager.pathsOf(job) },
                    answeredQuestionsOf = { job -> aiSyncManager.answeredQuestions(job) },
                    onNewEntry = {
                        navController.navigate(MainActivity.ROUTE_AI_ENTRY) {
                            launchSingleTop = true
                        }
                    },
                    onRefresh = {
                        if (aiSyncManager.poll()) {
                            AiJobSyncService.start(context)
                        }
                    },
                    onAnswer = { clientJobId, questionId, answer ->
                        aiSyncManager.recordAnswer(clientJobId, questionId, answer)
                        AiJobSyncService.start(context)
                    },
                    onSkipQuestions = { clientJobId ->
                        aiSyncManager.skipQuestions(clientJobId)
                    },
                    onCancel = { clientJobId -> aiSyncManager.cancelJob(clientJobId) },
                    onRetry = { clientJobId, text ->
                        val job = repository.getAiJob(clientJobId)
                        if (job != null) {
                            aiSyncManager.retryJob(
                                clientJobId = clientJobId,
                                newText = text,
                                thresholdMode = job.thresholdMode,
                                thresholdValue = job.thresholdValue
                            )
                            AiJobSyncService.start(context)
                        }
                    },
                    onDelete = { clientJobId ->
                        aiSyncManager.deleteJob(clientJobId)
                        updateWidgets()
                    },
                    onSetExcluded = { clientJobId, excluded ->
                        aiSyncManager.setExcludedFromTotals(clientJobId, excluded)
                        updateWidgets()
                    }
                )
                }
            }

            composable(
                route = "${MainActivity.ROUTE_PLANNING}/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.StringType })
            ) { entry ->
                val argId = entry.arguments?.getString("threadId").orEmpty()
                // A brand-new thread has no id until its first message reaches the
                // server, so the screen holds the id it gets back.
                var threadId by rememberSaveable(argId) {
                    mutableStateOf(if (argId == MainActivity.NEW_THREAD) "" else argId)
                }

                PlanningThreadScreen(
                    threadFlow = repository.getThreadFlow(threadId),
                    messagesFlow = repository.getThreadMessagesFlow(threadId),
                    proposalsOf = { message -> repository.parseProposals(message) },
                    appliedIndicesOf = { message -> repository.parseAppliedIndices(message) },
                    onSend = { text, images ->
                        val result = if (threadId.isBlank()) {
                            when (val started = planningManager.startThread(text, images)) {
                                is AiCallResult.Success -> {
                                    threadId = started.value
                                    null
                                }
                                is AiCallResult.Failure -> started.message
                            }
                        } else {
                            when (val sent = planningManager.sendMessage(threadId, text, images)) {
                                is AiCallResult.Success -> null
                                is AiCallResult.Failure -> sent.message
                            }
                        }
                        AiJobSyncService.start(context)
                        result
                    },
                    onApplyProposal = { messageId, index, proposal, savePreset ->
                        planningManager.applyProposal(messageId, index, proposal, savePreset)
                        updateWidgets()
                    },
                    onRefresh = {
                        if (planningManager.poll()) {
                            AiJobSyncService.start(context)
                        }
                    },
                    onCancel = {
                        if (threadId.isNotBlank()) planningManager.cancel(threadId)
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(MainActivity.ROUTE_AI_ENTRY) {
                AiEntryScreen(
                    aiSettingsFlow = repository.getAiSettingsFlow(),
                    onSubmit = { text, images, thresholdMode, thresholdValue ->
                        when (
                            val result = aiSyncManager.submitJob(
                                text = text,
                                imageUris = images,
                                thresholdMode = thresholdMode,
                                thresholdValue = thresholdValue
                            )
                        ) {
                            is AiCallResult.Success -> {
                                if (result.value.serverJobId == null) {
                                    // Photographed somewhere the server can't be
                                    // reached. It's saved, and goes out on its own.
                                    android.widget.Toast.makeText(
                                        context,
                                        "Saved. It'll be sent when your server is reachable.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    AiJobSyncService.start(context)
                                }
                                AiCallResult.Success(Unit)
                            }
                            is AiCallResult.Failure -> result
                        }
                    },
                    onDone = {
                        navController.navigate(Screen.AiLog.route) {
                            popUpTo(Screen.Dashboard.route)
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    setupSharing = {
                        SetupSharingCard(
                            settingsFlow = repository.getAiSettingsFlow(),
                            latestRelease = {
                                AiClient.latestRelease(repository.getAiSettings()).successOrNull
                            },
                            onInvite = { name, email ->
                                AiClient.createUser(repository.getAiSettings(), name, email)
                            },
                            onScanned = { setup ->
                                scope.launch {
                                    val current = repository.getAiSettings()
                                    repository.saveAiSettings(
                                        current.copy(
                                            serverUrl = setup.url,
                                            apiKey = setup.key,
                                            enabled = true
                                        )
                                    )
                                }
                            }
                        )
                    },
                    targetFlow = repository.getTargetFlow(),
                    widgetSettingsFlow = repository.getWidgetSettingsFlow(),
                    syncSettingsFlow = repository.getSyncSettingsFlow(),
                    aiSettingsFlow = repository.getAiSettingsFlow(),
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
                    onSaveAiSettings = { aiSettings ->
                        scope.launch {
                            repository.saveAiSettings(aiSettings)
                        }
                    },
                    onTestAiConnection = { url, key, pin ->
                        aiSyncManager.testConnection(url, key, pin)
                    },
                    onDiscoverServer = { aiSyncManager.rediscoverServer() },
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
