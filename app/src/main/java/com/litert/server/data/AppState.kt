package com.litert.server.data

import com.litert.server.download.DownloadProgress
import com.litert.server.download.ModelArtifact

enum class ServerStatus {
    STOPPED,
    DOWNLOADING,
    INITIALIZING,
    RUNNING,
    ERROR
}

data class ServerSnapshot(
    val status: ServerStatus = ServerStatus.STOPPED,
    val models: List<ModelArtifact> = emptyList(),
    val activeModelId: String? = null,
    val download: DownloadProgress? = null,
    val error: String? = null,
    val serverPort: Int? = null,
    val backend: String? = null,
    val backendError: String? = null,
    val apiToken: String = "",
    val hasHuggingFaceToken: Boolean = false,
    val useGpu: Boolean = true
)
