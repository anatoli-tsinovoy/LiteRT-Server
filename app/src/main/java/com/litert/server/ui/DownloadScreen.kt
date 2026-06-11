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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litert.server.data.AppStatus
import com.litert.server.download.ModelDescriptor

@Composable
fun DownloadScreen(
    status: AppStatus,
    progressPercent: Float,
    downloadedMb: Float,
    totalMb: Float,
    speedMbps: Float,
    etaSeconds: Int,
    errorMessage: String?,
    availableModels: List<ModelDescriptor>,
    installedModelIds: Set<String>,
    selectedModel: ModelDescriptor,
    onModelSelected: (ModelDescriptor) -> Unit,
    onAddHuggingFaceModel: (String) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onPickFile: () -> Unit
) {
    var customModelUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            Icons.Default.Download,
            contentDescription = null,
            tint = GreenPrimary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "LiteRT Server",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "On-device LLM via Google LiteRT-LM",
            color = Color.Gray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (status == AppStatus.MODEL_NOT_FOUND || status == AppStatus.DOWNLOAD_ERROR) {
            Text(
                "Select model",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            availableModels.forEach { model ->
                ModelOptionRow(
                    model = model,
                    selected = model.id == selectedModel.id,
                    installed = model.id in installedModelIds,
                    onClick = { onModelSelected(model) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SettingsCard {
                Text("Add compatible Hugging Face model", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Paste a repo URL or direct .litertlm URL. Repo URLs use the first non-web .litertlm file found.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = customModelUrl,
                    onValueChange = { customModelUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("https://huggingface.co/owner/repo") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = GreenPrimary,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedLabelColor = GreenPrimary,
                        unfocusedLabelColor = Color.Gray,
                        cursorColor = GreenPrimary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onAddHuggingFaceModel(customModelUrl)
                        customModelUrl = ""
                    },
                    enabled = customModelUrl.isNotBlank(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Hugging Face model")
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        when (status) {
            AppStatus.MODEL_NOT_FOUND -> {
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Download ${selectedModel.displayName}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onPickFile,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import .litertlm file for selected model", fontSize = 14.sp)
                }
            }

            AppStatus.DOWNLOADING -> {
                LinearProgressIndicator(
                    progress = { progressPercent },
                    modifier = Modifier.fillMaxWidth(),
                    color = GreenPrimary,
                    trackColor = Color(0xFF333333)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "${(progressPercent * 100).toInt()}%",
                    color = GreenPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "${"%.1f".format(downloadedMb)} MB / ${"%.0f".format(totalMb)} MB",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${"%.1f".format(speedMbps)} MB/s · ETA ${etaSeconds}s",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = GreenPrimary, modifier = Modifier.size(32.dp))
            }

            AppStatus.DOWNLOAD_ERROR -> {
                Text(
                    "Download failed",
                    color = Color(0xFFEF4444),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                errorMessage?.let {
                    Text(
                        it,
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Retry Download", fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onPickFile,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import .litertlm file for selected model", fontSize = 14.sp)
                }
            }

            AppStatus.INITIALIZING -> {
                CircularProgressIndicator(
                    color = GreenPrimary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Loading ${selectedModel.displayName} into GPU memory...",
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    "This may take 10–60 seconds on first launch",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            else -> {}
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ModelOptionRow(
    model: ModelDescriptor,
    selected: Boolean,
    installed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) GreenPrimary else Color(0xFF333333),
                shape = RoundedCornerShape(10.dp)
            )
            .background(
                color = if (selected) Color(0xFF0D2D0D) else Color(0xFF111111),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.displayName,
                    color = if (selected) GreenPrimary else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (installed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Installed", color = GreenPrimary, fontSize = 10.sp)
                }
            }
            Text(
                model.description,
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
        Text(
            "${"%.2f".format(model.estimatedGb)} GB",
            color = if (selected) GreenPrimary else Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = GreenPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
