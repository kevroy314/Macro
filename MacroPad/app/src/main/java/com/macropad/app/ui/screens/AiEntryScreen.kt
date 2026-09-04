package com.macropad.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.macropad.app.data.entity.AiSettings
import com.macropad.app.data.entity.ThresholdMode
import com.macropad.app.net.AiCallResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Photograph a meal (or a receipt), add a sentence of context, send it off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiEntryScreen(
    aiSettingsFlow: Flow<AiSettings?>,
    onSubmit: suspend (text: String, images: List<Uri>, thresholdMode: String, thresholdValue: Float) -> AiCallResult<Unit>,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by aiSettingsFlow.collectAsState(initial = null)
    val current = settings ?: AiSettings()

    var text by remember { mutableStateOf("") }
    val images = remember { mutableStateListOf<Uri>() }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var overrideThreshold by remember { mutableStateOf(false) }
    var thresholdMode by remember { mutableStateOf(ThresholdMode.PERCENT) }
    var thresholdValue by remember { mutableStateOf("") }

    LaunchedEffect(current.thresholdMode, current.thresholdValue) {
        if (!overrideThreshold) {
            thresholdMode = current.thresholdMode
            thresholdValue = current.thresholdValue.toInt().toString()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declined just means no inline follow-up prompts; the job still runs. */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris -> uris.take(MAX_IMAGES - images.size).forEach { images.add(it) } }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) images.add(uri)
        pendingCameraUri = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp)
    ) {
        Text(
            text = "AI Estimate",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Photograph the food, a label, or a receipt. Add anything the picture " +
                "doesn't say — portion, what you left, where it came from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (!current.isConfigured) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AI server not set up", fontWeight = FontWeight.Bold)
                    Text(
                        "Add the server address and API key in Settings to use this.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images) { uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        IconButton(
                            onClick = { images.remove(uri) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Remove photo",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val uri = createCaptureUri(context)
                    pendingCameraUri = uri
                    takePicture.launch(uri)
                },
                enabled = images.size < MAX_IMAGES
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Camera")
            }
            OutlinedButton(
                onClick = {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = images.size < MAX_IMAGES
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Gallery")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What did you eat?") },
            placeholder = { Text("e.g. half the burrito, extra guac, from Torchy's") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            minLines = 3
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = overrideThreshold,
                onCheckedChange = { overrideThreshold = it }
            )
            Column {
                Text("Ask me more / less often", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (overrideThreshold) {
                        "Just for this entry"
                    } else {
                        "Using your default: ${current.thresholdValue.toInt()}" +
                            if (current.thresholdMode == ThresholdMode.PERCENT) "%" else " cal"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        if (overrideThreshold) {
            Spacer(modifier = Modifier.height(8.dp))
            ThresholdPicker(
                mode = thresholdMode,
                value = thresholdValue,
                onModeChange = { thresholdMode = it },
                onValueChange = { thresholdValue = it }
            )
        }

        error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                error = null
                submitting = true
                scope.launch {
                    val value = thresholdValue.toFloatOrNull() ?: current.thresholdValue
                    val result = onSubmit(
                        text,
                        images.toList(),
                        thresholdMode.wireValue,
                        value
                    )
                    submitting = false
                    when (result) {
                        is AiCallResult.Success -> onDone()
                        is AiCallResult.Failure -> error = result.message
                    }
                }
            },
            enabled = !submitting && current.isConfigured &&
                (text.isNotBlank() || images.isNotEmpty()),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sending…")
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Estimate macros")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThresholdPicker(
    mode: ThresholdMode,
    value: String,
    onModeChange: (ThresholdMode) -> Unit,
    onValueChange: (String) -> Unit
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ThresholdMode.PERCENT,
                onClick = { onModeChange(ThresholdMode.PERCENT) },
                label = { Text("Percent") }
            )
            FilterChip(
                selected = mode == ThresholdMode.ABSOLUTE,
                onClick = { onModeChange(ThresholdMode.ABSOLUTE) },
                label = { Text("Calories") }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.filter { c -> c.isDigit() }) },
            label = {
                Text(
                    if (mode == ThresholdMode.PERCENT) {
                        "Ask if it could change the total by more than (%)"
                    } else {
                        "Ask if it could change the total by more than (cal)"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

private const val MAX_IMAGES = 6

/** Shared with the planning composer, which offers the same camera button. */
internal fun createCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "ai_captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
