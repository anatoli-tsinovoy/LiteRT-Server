package com.litert.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litert.server.download.ModelArtifact
import com.litert.server.download.ModelDescriptor

@Composable
fun SettingsScreen(
    selectedModel: ModelDescriptor,
    installedModels: List<ModelArtifact>,
    isGpu: Boolean,
    hasHuggingFaceToken: Boolean,
    onSelectModel: (ModelDescriptor) -> Unit,
    onDeleteModel: (ModelDescriptor) -> Unit,
    onDeleteAllModels: () -> Unit,
    onSaveHuggingFaceToken: (String) -> Unit,
    onClearHuggingFaceToken: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit
) {
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var maxTokens by remember { mutableFloatStateOf(1024f) }
    var useGpu by remember { mutableStateOf(isGpu) }
    var modelToDelete by remember { mutableStateOf<ModelDescriptor?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var huggingFaceToken by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))

        SettingsCard {
            Text("Active Model", color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(selectedModel.displayName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(selectedModel.id, color = Color.Gray, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Text("Installed Models", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            if (installedModels.isEmpty()) {
                Text("No installed models yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                installedModels.forEach { artifact ->
                    InstalledModelRow(
                        artifact = artifact,
                        selected = artifact.model.id == selectedModel.id,
                        onSelect = { onSelectModel(artifact.model) },
                        onDelete = { modelToDelete = artifact.model }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Text("Hugging Face Login", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (hasHuggingFaceToken) {
                    "Token saved. Downloads and repo lookups will authenticate with Hugging Face."
                } else {
                    "Paste a Hugging Face access token to improve rate limits and access gated models you accepted."
                },
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = huggingFaceToken,
                onValueChange = { huggingFaceToken = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("hf_ token") },
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSaveHuggingFaceToken(huggingFaceToken)
                        huggingFaceToken = ""
                    },
                    enabled = huggingFaceToken.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text("Save Token", color = Color.Black)
                }
                OutlinedButton(
                    onClick = {
                        huggingFaceToken = ""
                        onClearHuggingFaceToken()
                    },
                    enabled = hasHuggingFaceToken
                ) {
                    Text("Clear")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("GPU Acceleration", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "LiteRT-LM GPU backend with CPU fallback",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = useGpu,
                    onCheckedChange = { useGpu = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = GreenPrimary, checkedTrackColor = Color(0xFF1A3A1A))
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Temperature", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("${"%.2f".format(temperature)}", color = GreenPrimary)
            }
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0.1f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = GreenPrimary,
                    activeTrackColor = GreenPrimary,
                    inactiveTrackColor = Color(0xFF333333)
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0.1", color = Color.Gray, fontSize = 11.sp)
                Text("1.0", color = Color.Gray, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Max Tokens", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("${maxTokens.toInt()}", color = GreenPrimary)
            }
            Slider(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                valueRange = 128f..2048f,
                steps = 14,
                colors = SliderDefaults.colors(
                    thumbColor = GreenPrimary,
                    activeTrackColor = GreenPrimary,
                    inactiveTrackColor = Color(0xFF333333)
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("128", color = Color.Gray, fontSize = 11.sp)
                Text("2048", color = Color.Gray, fontSize = 11.sp)
            }
        }

        SettingsCard {
            Text("Diagnostics", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Copy the app-private diagnostics log after a crash or failed request. It includes engine lifecycle, API errors, device info, memory, and stack traces.",
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCopyDiagnostics,
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text("Copy Diagnostics", color = Color.Black)
                }
                OutlinedButton(onClick = onClearDiagnostics) {
                    Text("Clear")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showDeleteAllDialog = true },
            enabled = installedModels.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Delete All Models & Cache")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("LiteRT Server v1.0", color = Color.Gray, fontSize = 12.sp)
        Text("LiteRT-LM SDK 0.10.0", color = Color.Gray, fontSize = 12.sp)
    }

    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("This deletes the local model artifact. It can be downloaded or imported again later.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteModel(model)
                    modelToDelete = null
                }) { Text("Delete", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete all models?") },
            text = { Text("This deletes every downloaded model artifact and app cache files.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAllModels()
                    showDeleteAllDialog = false
                }) { Text("Delete All", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InstalledModelRow(
    artifact: ModelArtifact,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) GreenPrimary else Color(0xFF333333),
                shape = RoundedCornerShape(10.dp)
            )
            .background(
                color = if (selected) Color(0xFF0D2D0D) else Color(0xFF111111),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(artifact.model.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (selected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text("${"%.1f".format(artifact.sizeMb)} MB", color = Color.Gray, fontSize = 12.sp)
            Text(artifact.path, color = Color.Gray, fontSize = 10.sp)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete model", tint = Color(0xFFEF4444))
        }
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}
