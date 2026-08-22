package com.litert.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.litert.server.download.ModelArtifact
import com.litert.server.service.LLMForegroundService

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF356A2C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F39E),
    onPrimaryContainer = Color(0xFF082100),
    secondary = Color(0xFF52634D),
    tertiary = Color(0xFF386568),
    background = Color(0xFFF7FAF5),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDFE4DB),
    onSurfaceVariant = Color(0xFF43483F),
    outline = Color(0xFF74796F),
    error = Color(0xFFBA1A1A),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA6D68A),
    onPrimary = Color(0xFF173810),
    primaryContainer = Color(0xFF24501B),
    onPrimaryContainer = Color(0xFFB7F59F),
    secondary = Color(0xFFB9CCB3),
    tertiary = Color(0xFFA0CFD2),
    background = Color(0xFF101310),
    onBackground = Color(0xFFE1E4DD),
    surface = Color(0xFF1B1E1B),
    onSurface = Color(0xFFE1E4DD),
    surfaceVariant = Color(0xFF292E29),
    onSurfaceVariant = Color(0xFFC2C8BD),
    outline = Color(0xFF8C9388),
    error = Color(0xFFFFB4AB),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LLMForegroundService.refresh(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
            ) {
                ServerScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerScreen() {
    val snapshot by LLMForegroundService.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var directUrl by rememberSaveable { mutableStateOf("") }
    var nativeTokens by rememberSaveable { mutableStateOf("") }
    var useGpu by remember { mutableStateOf(snapshot.useGpu) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val activeArtifact = snapshot.models.firstOrNull { it.model.id == snapshot.activeModelId }
        ?: snapshot.models.firstOrNull()
    val activeModel = activeArtifact?.model
    val statusName = snapshot.status.name
    val isDownloading = statusName == "DOWNLOADING"
    val isInitializing = statusName == "INITIALIZING"
    val isRunning = statusName == "RUNNING"
    val isBusy = isDownloading || isInitializing
    val canChangeModel = !isBusy && !isRunning
    val isInstalled = activeArtifact?.isInstalled == true
    val validNativeTokens = nativeTokens.toIntOrNull()
    val endpoint = "http://127.0.0.1:${snapshot.serverPort ?: 8080}"
    val modelId = activeModel?.id ?: snapshot.activeModelId ?: "qwen3-0.6b"
    val apiToken = snapshot.apiToken

    LaunchedEffect(snapshot.useGpu) {
        useGpu = snapshot.useGpu
    }
    LaunchedEffect(activeModel?.id, activeModel?.nativeMaxTokens) {
        nativeTokens = activeModel?.nativeMaxTokens?.toString().orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LiteRT Server", fontWeight = FontWeight.Bold)
                        Text(
                            "Local OpenAI-compatible endpoint",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = { StatusPill(statusName) }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Model") {
                if (snapshot.models.isEmpty()) {
                    Text(
                        "Loading model catalog…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            enabled = canChangeModel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                activeModel?.displayName ?: "Choose a model",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(if (modelMenuExpanded) "▲" else "▼")
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.92f)
                        ) {
                            snapshot.models.forEach { artifact ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(artifact.model.displayName)
                                            Text(
                                                modelAvailability(artifact),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        modelMenuExpanded = false
                                        if (canChangeModel) {
                                            LLMForegroundService.selectModel(context, artifact.model.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    activeModel?.let { model ->
                        Text(
                            model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${model.filename} · context ${model.contextWindowTokens} tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionCard("Download") {
                OutlinedTextField(
                    value = directUrl,
                    onValueChange = { directUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canChangeModel,
                    singleLine = true,
                    label = { Text("Direct Hugging Face .litertlm URL") },
                    placeholder = { Text("https://huggingface.co/.../*.litertlm") },
                    supportingText = {
                        Text("Use a direct https://huggingface.co/.../resolve/... URL; repository pages are not downloadable.")
                    }
                )
                Button(
                    onClick = {
                        LLMForegroundService.addAndDownload(context, directUrl.trim())
                        directUrl = ""
                    },
                    enabled = directUrl.trim().isNotEmpty() && canChangeModel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add and download model")
                }

                snapshot.download?.let { progress ->
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { progress.progressPercent.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${(progress.progressPercent * 100).toInt()}% · ${formatMegabytes(progress.downloadedMb)} / ${formatMegabytes(progress.totalMb)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${formatMegabytes(progress.speedMbps)} MB/s · ETA ${progress.etaSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isDownloading) {
                    OutlinedButton(
                        onClick = { LLMForegroundService.cancelDownload(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel download")
                    }
                } else if (activeModel != null && !isInstalled && !isRunning && !isInitializing) {
                    Button(
                        onClick = { LLMForegroundService.downloadSelected(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download ${activeModel.displayName}")
                    }
                } else if (activeArtifact != null && isInstalled) {
                    Text(
                        "Installed · ${formatBytes(activeArtifact.installedBytes)}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SectionCard("Runtime configuration") {
                Text(
                    "Native token memory limit",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = nativeTokens,
                    onValueChange = { nativeTokens = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = activeModel != null && canChangeModel,
                    singleLine = true,
                    label = { Text("Native max tokens") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text("Higher values use more native memory; maximum is the model context window.")
                    }
                )
                Button(
                    onClick = {
                        activeModel?.let { model ->
                            validNativeTokens?.let { value ->
                                LLMForegroundService.setNativeMaxTokens(context, model.id, value)
                            }
                        }
                    },
                    enabled = activeModel != null && validNativeTokens != null && canChangeModel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save token limit")
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Prefer GPU", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Applied the next time you start the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useGpu,
                        onCheckedChange = { useGpu = it },
                        enabled = !isRunning && !isInitializing
                    )
                }
            }

            SectionCard("Server control") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(statusLabel(statusName), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isRunning) "Listening only on 127.0.0.1"
                            else "Start explicitly after the model is installed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        snapshot.backend?.let { backend ->
                            Text(
                                "Backend: $backend",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        snapshot.backendError?.let { backendError ->
                            Text(
                                "GPU fallback: $backendError",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (isInitializing) {
                        LinearProgressIndicator(modifier = Modifier.width(72.dp))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { LLMForegroundService.startServer(context, useGpu) },
                        enabled = isInstalled && !isBusy && !isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start")
                    }
                    OutlinedButton(
                        onClick = { LLMForegroundService.stopServer(context) },
                        enabled = isRunning || isInitializing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop")
                    }
                }
            }

            if (!snapshot.error.isNullOrBlank()) {
                SectionCard("Error") {
                    Text(
                        snapshot.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            SectionCard("Connection") {
                CopyableValue("Endpoint", endpoint, context)
                CopyableValue("Model", modelId, context)
                CopyableValue(
                    "API token",
                    apiToken.ifBlank { "Start the server to load the token" },
                    context,
                    enabled = apiToken.isNotBlank()
                )
                CopyableValue(
                    "curl",
                    curlCommand(endpoint, modelId, apiToken),
                    context,
                    monospace = true
                )
                Text(
                    "The API is localhost-only. Use the bearer token for /v1/models and /v1/chat/completions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatusPill(statusName: String) {
    val color = statusColor(statusName)
    Surface(
        color = color.copy(alpha = 0.18f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 12.dp)
    ) {
        Text(
            statusLabel(statusName),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CopyableValue(
    label: String,
    value: String,
    context: Context,
    enabled: Boolean = true,
    monospace: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { copyToClipboard(context, label, value) },
                enabled = enabled
            ) {
                Text("Copy")
            }
        }
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            style = if (monospace) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun modelAvailability(artifact: ModelArtifact): String = when {
    artifact.isInstalled -> "Installed"
    artifact.partialBytes > 0L -> "Partial download · ${formatBytes(artifact.partialBytes)}"
    else -> "Not downloaded"
}

private fun statusLabel(statusName: String): String = when (statusName) {
    "DOWNLOADING" -> "Downloading"
    "INITIALIZING" -> "Initializing"
    "RUNNING" -> "Running"
    "ERROR" -> "Error"
    else -> "Stopped"
}

@Composable
private fun statusColor(statusName: String): Color = when (statusName) {
    "RUNNING" -> MaterialTheme.colorScheme.primary
    "DOWNLOADING", "INITIALIZING" -> MaterialTheme.colorScheme.tertiary
    "ERROR" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private fun formatMegabytes(value: Float): String = "%.1f".format(value)

private fun curlCommand(endpoint: String, modelId: String, token: String): String {
    val bearer = token.ifBlank { "<api-token>" }
    return """curl "$endpoint/v1/chat/completions" \
  -H "Authorization: Bearer $bearer" \
  -H "Content-Type: application/json" \
  -d '{"model":"$modelId","messages":[{"role":"user","content":"Hello"}]}'"""
}
