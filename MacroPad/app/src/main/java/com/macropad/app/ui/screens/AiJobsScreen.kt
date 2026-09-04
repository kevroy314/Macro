package com.macropad.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.macropad.app.data.entity.AiAnsweredQuestion
import com.macropad.app.data.entity.AiJob
import com.macropad.app.data.entity.AiQuestion
import com.macropad.app.data.entity.AiResult
import com.macropad.app.ui.theme.CaloriesColor
import com.macropad.app.ui.theme.CarbsColor
import com.macropad.app.ui.theme.FatColor
import com.macropad.app.ui.theme.ProteinColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The in-app log of AI estimation jobs: what's running, what needs an answer, and
 * what has already been added to your day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiJobsScreen(
    jobsFlow: Flow<List<AiJob>>,
    parseResult: (AiJob) -> AiResult?,
    imagePathsOf: (AiJob) -> List<String>,
    answeredQuestionsOf: (AiJob) -> List<AiAnsweredQuestion>,
    onNewEntry: () -> Unit,
    onRefresh: suspend () -> Unit,
    onAnswer: suspend (clientJobId: String, questionId: String, answer: String) -> Unit,
    onSkipQuestions: suspend (clientJobId: String) -> Unit,
    onCancel: suspend (clientJobId: String) -> Unit,
    onRetry: suspend (clientJobId: String, text: String) -> Unit,
    onDelete: suspend (clientJobId: String) -> Unit,
    onCorrect: suspend (clientJobId: String, text: String) -> Unit,
    onSetExcluded: suspend (clientJobId: String, excluded: Boolean) -> Unit
) {
    val jobs by jobsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editingJob by remember { mutableStateOf<AiJob?>(null) }
    var confirmDelete by remember { mutableStateOf<AiJob?>(null) }
    var correcting by remember { mutableStateOf<AiJob?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        refreshing = true
        try {
            onRefresh()
        } finally {
            refreshing = false
        }
    }

    // Pull whatever the daemon knows whenever this screen opens, so a job whose
    // result landed while the app was closed shows up without waiting for a poll.
    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AI Estimates",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { scope.launch { refresh() } },
                    enabled = !refreshing
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                FloatingActionButton(
                    onClick = onNewEntry,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = "New AI entry")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (jobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No AI estimates yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "Snap a meal or a receipt and let it work the numbers out",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(jobs, key = { it.clientJobId }) { job ->
                    AiJobCard(
                        job = job,
                        result = parseResult(job),
                        imagePaths = imagePathsOf(job),
                        answered = answeredQuestionsOf(job),
                        onAnswer = { questionId, answer ->
                            scope.launch { onAnswer(job.clientJobId, questionId, answer) }
                        },
                        onSkipQuestions = { scope.launch { onSkipQuestions(job.clientJobId) } },
                        onCancel = { scope.launch { onCancel(job.clientJobId) } },
                        onEdit = { editingJob = job },
                        onDelete = { confirmDelete = job },
                        onCorrect = { correcting = job },
                        onToggleExcluded = {
                            scope.launch {
                                onSetExcluded(job.clientJobId, !job.excludedFromTotals)
                            }
                        }
                    )
                }
            }
        }
    }

    editingJob?.let { job ->
        EditAndRestartDialog(
            job = job,
            onDismiss = { editingJob = null },
            onConfirm = { newText ->
                scope.launch { onRetry(job.clientJobId, newText) }
                editingJob = null
            }
        )
    }

    correcting?.let { job ->
        var correction by remember(job.clientJobId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { correcting = null },
            title = { Text("What did it get wrong?") },
            text = {
                Column {
                    Text(
                        "It keeps everything else it worked out and just fixes this. " +
                            "Your totals update to match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = correction,
                        onValueChange = { correction = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        placeholder = {
                            Text("e.g. I had one more of the same, so twice that amount")
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = correction
                        correcting = null
                        scope.launch { onCorrect(job.clientJobId, text) }
                    },
                    enabled = correction.isNotBlank()
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { correcting = null }) { Text("Cancel") }
            }
        )
    }

    confirmDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete estimate") },
            text = {
                Text(
                    if (job.appliedEntryId != null && !job.excludedFromTotals) {
                        "This removes the entry from today's totals as well. To keep the " +
                            "record but drop the macros, use \"Exclude from totals\" instead."
                    } else {
                        "This removes the estimate from the log."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { onDelete(job.clientJobId) }
                    confirmDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AiJobCard(
    job: AiJob,
    result: AiResult?,
    imagePaths: List<String>,
    answered: List<AiAnsweredQuestion>,
    onAnswer: (String, String) -> Unit,
    onSkipQuestions: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCorrect: () -> Unit,
    onToggleExcluded: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result?.preset_name?.takeIf { it.isNotBlank() }
                            ?: job.promptText.takeIf { it.isNotBlank() }
                            ?: "Photo estimate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (job.excludedFromTotals) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = timeLabel(job.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                StatusChip(job)
            }

            if (job.excludedFromTotals) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Not counted in your totals",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // What it is doing right now, straight from the agent's stream. Without
            // this a running estimate shows nothing for a couple of minutes, which
            // is indistinguishable from being stuck.
            job.progress.takeIf { it.isNotBlank() && job.isActive }?.let { line ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp).padding(top = 2.dp),
                        strokeWidth = 1.5.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (imagePaths.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    imagePaths.take(4).forEach { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }

            if (result != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MacroValue("P", result.total.protein_g, ProteinColor)
                    MacroValue("C", result.total.carbs_g, CarbsColor)
                    MacroValue("F", result.total.fat_g, FatColor)
                    Text(
                        text = "${result.total.calories} cal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CaloriesColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (job.excludedFromTotals) {
                    Text(
                        text = "Not counted in your totals",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // The reasoning is the most useful part of an estimate — show it
                // without making the user find a toggle first.
                if (result.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        result.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (job.status == AiJob.STATUS_RUNNING || job.status == AiJob.STATUS_QUEUED ||
                job.status == AiJob.STATUS_PENDING_UPLOAD
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            job.error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            val unanswered = if (result != null && !job.questionsResolved) {
                result.follow_up_questions.filter { q -> answered.none { it.id == q.id } }
            } else {
                emptyList()
            }

            if (unanswered.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                unanswered.forEach { question ->
                    QuestionCard(
                        question = question,
                        onAnswer = { answer -> onAnswer(question.id, answer) },
                        onSkip = onSkipQuestions
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (answered.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                answered.forEach { entry ->
                    if (entry.question.isNotBlank()) {
                        Text(
                            entry.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = ProteinColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            entry.answer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            if (expanded && result != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text(
                        "Item",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "P / C / F",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                result.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium)
                            if (item.qty.isNotBlank() || item.assumptions.isNotBlank()) {
                                Text(
                                    listOfNotNull(
                                        item.qty.takeIf { it.isNotBlank() },
                                        item.assumptions.takeIf { it.isNotBlank() }
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${item.protein_g} / ${item.carbs_g} / ${item.fat_g}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (result.sources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Sources",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    result.sources.forEach { source ->
                        Text(
                            source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (result != null) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Less" else "Details")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))

                if (job.isActive) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                if (!job.isActive) {
                    // Correcting is the common need — it got the portion or the item
                    // wrong — and it costs one turn instead of researching again.
                    if (job.serverJobId != null) {
                        TextButton(onClick = onCorrect) { Text("Correct") }
                    }
                    if (job.appliedEntryId != null) {
                        // Named, not an eye icon. Nobody guesses what the eye means,
                        // and this is the control people reach for to undo a mistake.
                        TextButton(onClick = onToggleExcluded) {
                            Text(
                                if (job.excludedFromTotals) "Count it" else "Don't count",
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit and re-run")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: AiQuestion,
    onAnswer: (String) -> Unit,
    onSkip: () -> Unit
) {
    var answer by remember(question.id) { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                question.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (question.why.isNotBlank()) {
                Text(
                    "${question.why} (±${question.est_calorie_swing.toInt()} cal)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                placeholder = { Text("Your answer") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onSkip) { Text("Keep estimate") }
                TextButton(
                    onClick = { onAnswer(answer.trim()) },
                    enabled = answer.isNotBlank()
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun StatusChip(job: AiJob) {
    val (label, color) = when (job.status) {
        AiJob.STATUS_PENDING_UPLOAD -> "Uploading" to MaterialTheme.colorScheme.tertiary
        AiJob.STATUS_QUEUED -> "Queued" to MaterialTheme.colorScheme.tertiary
        AiJob.STATUS_RUNNING -> "Working" to MaterialTheme.colorScheme.tertiary
        AiJob.STATUS_NEEDS_INPUT -> "Question" to FatColor
        AiJob.STATUS_COMPLETED -> "Done" to ProteinColor
        AiJob.STATUS_FAILED -> "Failed" to MaterialTheme.colorScheme.error
        AiJob.STATUS_CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.outline
        AiJob.STATUS_SUPERSEDED -> "Replaced" to MaterialTheme.colorScheme.outline
        else -> job.status to MaterialTheme.colorScheme.outline
    }
    Surface(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun MacroValue(label: String, value: Int, color: Color) {
    Text(
        text = "$label: ${value}g",
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun EditAndRestartDialog(
    job: AiJob,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(job.promptText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit and re-run") },
        text = {
            Column {
                Text(
                    "The same photos are reused. Correct anything that was wrong or missing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What did you eat?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Re-run") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
