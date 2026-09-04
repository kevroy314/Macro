package com.macropad.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.macropad.app.data.entity.AiProposal
import com.macropad.app.data.entity.AiThread
import com.macropad.app.data.entity.AiThreadMessage
import com.macropad.app.ui.MarkdownText
import com.macropad.app.ui.theme.CaloriesColor
import com.macropad.app.ui.theme.CarbsColor
import com.macropad.app.ui.theme.FatColor
import com.macropad.app.ui.theme.ProteinColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * One planning conversation: a chat that can see the day's numbers and hand back
 * entries to log.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanningThreadScreen(
    threadFlow: Flow<AiThread?>,
    messagesFlow: Flow<List<AiThreadMessage>>,
    proposalsOf: (AiThreadMessage) -> List<AiProposal>,
    appliedIndicesOf: (AiThreadMessage) -> Set<Int>,
    onSend: suspend (text: String, images: List<Uri>) -> String?,
    onApplyProposal: suspend (messageId: String, index: Int, proposal: AiProposal, savePreset: Boolean) -> Unit,
    onRefresh: suspend () -> Unit,
    onCancel: suspend () -> Unit,
    onBack: () -> Unit
) {
    val thread by threadFlow.collectAsState(initial = null)
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var draft by remember { mutableStateOf("") }
    val pendingImages = remember { mutableStateListOf<Uri>() }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris -> uris.forEach { pendingImages.add(it) } }

    val busy = thread?.isBusy == true || sending

    fun lastIndex() = (messages.size - 1 + if (busy) 1 else 0).coerceAtLeast(0)

    // Keep the newest turn in view as replies arrive.
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(lastIndex())
        }
    }

    // Opening the keyboard shrinks the list from the bottom, so whatever you were
    // reading slides out of view and you have to scroll back down. Follow it.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(lastIndex())
        }
    }

    // The keyboard animates open over several frames and the list is re-measured on
    // each one; a single scroll at the start lands short on a full thread.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            listState.scrollToItem(lastIndex())
        }
    }

    // A reply can land while this screen is open, or long after it was sent.
    LaunchedEffect(Unit) { onRefresh() }

    // targetSdk 35 means Android 15 draws this edge-to-edge whether we ask or not,
    // so windowSoftInputMode alone no longer keeps the composer above the keyboard —
    // the IME inset has to be consumed here.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = thread?.displayTitle ?: "New plan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (busy) {
                TextButton(onClick = { scope.launch { onCancel() } }) { Text("Stop") }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    proposals = proposalsOf(message),
                    applied = appliedIndicesOf(message),
                    onApply = { index, proposal, savePreset ->
                        scope.launch {
                            onApplyProposal(message.id, index, proposal, savePreset)
                        }
                    }
                )
            }
            if (busy) {
                item {
                    // What the assistant is doing, straight from the stream: a short
                    // status while it thinks and searches, then the reply itself as it
                    // is written. A silent two-minute spinner reads as a hang.
                    val live = thread?.progress?.takeIf { it.isNotBlank() }
                    val isAnswerForming = (live?.length ?: 0) > 60

                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = if (isAnswerForming) {
                            Alignment.Top
                        } else {
                            Alignment.CenterVertically
                        }
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (isAnswerForming) {
                            MarkdownText(
                                text = live!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                live ?: "Working it out…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        thread?.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingImages.forEach { uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                        )
                        IconButton(
                            onClick = { pendingImages.remove(uri) },
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = "Remove")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(
                onClick = {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !busy
            ) {
                Icon(Icons.Default.Image, contentDescription = "Attach photo")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ask about what's left…") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    val text = draft.trim()
                    val images = pendingImages.toList()
                    if (text.isBlank() && images.isEmpty()) return@FilledIconButton
                    error = null
                    sending = true
                    draft = ""
                    pendingImages.clear()
                    scope.launch {
                        error = onSend(text, images)
                        sending = false
                    }
                },
                enabled = !busy && (draft.isNotBlank() || pendingImages.isNotEmpty())
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: AiThreadMessage,
    proposals: List<AiProposal>,
    applied: Set<Int>,
    onApply: (Int, AiProposal, Boolean) -> Unit
) {
    val isUser = message.role == AiThreadMessage.ROLE_USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.imageCount > 0) {
                    Text(
                        "📎 ${message.imageCount} photo${if (message.imageCount > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (message.text.isNotBlank()) {
                    if (message.role == "assistant") {
                        // Replies cite sources as markdown links; a plain Text shows
                        // the brackets.
                        MarkdownText(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        proposals.forEachIndexed { index, proposal ->
            Spacer(modifier = Modifier.height(6.dp))
            ProposalCard(
                proposal = proposal,
                alreadyApplied = index in applied,
                onApply = { savePreset -> onApply(index, proposal, savePreset) }
            )
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: AiProposal,
    alreadyApplied: Boolean,
    onApply: (Boolean) -> Unit
) {
    var savePreset by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        proposal.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (proposal.qty.isNotBlank()) {
                        Text(
                            proposal.qty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Text(
                    "${proposal.calories} cal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CaloriesColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("P ${proposal.protein_g}g", color = ProteinColor,
                    style = MaterialTheme.typography.bodySmall)
                Text("C ${proposal.carbs_g}g", color = CarbsColor,
                    style = MaterialTheme.typography.bodySmall)
                Text("F ${proposal.fat_g}g", color = FatColor,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (proposal.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    proposal.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (alreadyApplied) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = ProteinColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Added to today",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProteinColor
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = savePreset, onCheckedChange = { savePreset = it })
                    Text(
                        "Save as preset",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { onApply(savePreset) }) {
                        Icon(Icons.Default.Add, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add to today")
                    }
                }
            }
        }
    }
}
