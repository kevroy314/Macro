package com.macropad.app.ui.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.macropad.app.ai.SetupLink
import com.macropad.app.data.entity.AiSettings
import com.macropad.app.net.AiCallResult
import com.macropad.app.net.ServerRelease
import com.macropad.app.net.ServerUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Moving the app and its settings to another phone.
 *
 * Three jobs, all of them "point that device at this server": your own second
 * device, someone else's phone (which needs its own key so your logs stay yours),
 * and this device receiving a code from somewhere else.
 */
@Composable
fun SetupSharingCard(
    settingsFlow: Flow<AiSettings?>,
    latestRelease: suspend () -> ServerRelease?,
    onInvite: suspend (name: String, email: String) -> AiCallResult<ServerUser>,
    onScanned: (SetupLink.Setup) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsFlow.collectAsState(initial = null)
    val current = settings ?: AiSettings()

    var showing by remember { mutableStateOf<QrPayload?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<ServerRelease?>(null) }

    LaunchedEffect(current.isConfigured) {
        if (current.isConfigured) release = latestRelease()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCode2, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Share & Invite",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Move the app and its settings to another phone by scanning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    showing = QrPayload(
                        title = "Set up another device",
                        subtitle = "Scan from MacroPad → Settings → Scan setup code. " +
                            "This shares your own key, so both devices see the same log.",
                        content = SetupLink.encode(
                            SetupLink.Setup(current.baseUrl, current.apiKey)
                        )
                    )
                },
                enabled = current.isConfigured,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Set up my other device")
            }

            Spacer(modifier = Modifier.height(8.dp))

            release?.let { rel ->
                OutlinedButton(
                    onClick = {
                        showing = QrPayload(
                            title = "Download MacroPad ${rel.versionName}",
                            subtitle = "Scan with any camera — it opens a download link " +
                                "in the browser. Allow the install when prompted.",
                            content = SetupLink.downloadUrl(
                                current.baseUrl, rel.file, current.apiKey
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share the app itself")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = { inviting = true },
                enabled = current.isConfigured,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Invite someone else")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    status = null
                    scanSetupCode(context) { result ->
                        result
                            .onSuccess { raw ->
                                val setup = SetupLink.decode(raw)
                                if (setup == null) {
                                    status = "That isn't a MacroPad setup code"
                                } else {
                                    onScanned(setup)
                                    status = "Connected to ${setup.url}"
                                }
                            }
                            .onFailure { status = it.message ?: "Could not scan" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan a setup code")
            }

            status?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    showing?.let { payload ->
        QrDialog(payload = payload, onDismiss = { showing = null })
    }

    if (inviting) {
        InviteDialog(
            onDismiss = { inviting = false },
            onInvite = { name, email ->
                inviting = false
                status = "Creating…"
                scope.launch {
                    when (val result = onInvite(name, email)) {
                        is AiCallResult.Success -> {
                            status = null
                            showing = QrPayload(
                                title = "${result.value.name}'s setup code",
                                subtitle = "On their phone: install MacroPad, then " +
                                    "Settings → Scan a setup code. Their entries and " +
                                    "plans stay private to them.",
                                content = SetupLink.encode(
                                    SetupLink.Setup(
                                        current.baseUrl,
                                        result.value.key,
                                        result.value.name
                                    )
                                )
                            )
                        }
                        is AiCallResult.Failure -> status = result.message
                    }
                }
            }
        )
    }
}

data class QrPayload(val title: String, val subtitle: String, val content: String)

@Composable
private fun QrDialog(payload: QrPayload, onDismiss: () -> Unit) {
    val bitmap = remember(payload.content) { SetupLink.qrBitmap(payload.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(payload.title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    payload.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (bitmap != null) {
                    // On a white plate: scanners struggle with inverted codes, and
                    // the rest of this app is black.
                    Surface(color = androidx.compose.ui.graphics.Color.White) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR code",
                            modifier = Modifier.size(260.dp).padding(8.dp)
                        )
                    }
                } else {
                    Text("Could not render the code", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun InviteDialog(onDismiss: () -> Unit, onInvite: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite someone") },
        text = {
            Column {
                Text(
                    "They get their own key. Their photos, estimates and plans are " +
                        "theirs alone — neither of you can see the other's.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Google address (optional)") },
                    placeholder = { Text("for the web log") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onInvite(name.trim(), email.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Create key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Scans using Play Services' own scanner UI.
 *
 * Deliberately not an in-app camera: declaring CAMERA would make the existing
 * photo-capture flow require a runtime permission it doesn't need today.
 */
private fun scanSetupCode(context: Context, onResult: (Result<String>) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { barcode -> onResult(Result.success(barcode.rawValue.orEmpty())) }
        .addOnCanceledListener { onResult(Result.failure(Exception("Scan cancelled"))) }
        .addOnFailureListener { error ->
            onResult(Result.failure(Exception(error.message ?: "Scanner unavailable")))
        }
}
