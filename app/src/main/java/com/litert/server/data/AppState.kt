package com.litert.server.data

import com.litert.server.download.DownloadProgress
import com.litert.server.download.ModelArtifact

const val DEFAULT_SERVER_PORT = 8080
const val MIN_SERVER_PORT = 1024
const val MAX_SERVER_PORT = 65535

enum class ServerStatus {
    STOPPED,
    CONFIGURING,
    DOWNLOADING,
    INITIALIZING,
    RUNNING,
    STOPPING,
    ERROR
}

data class ServerSnapshot(
    val status: ServerStatus = ServerStatus.STOPPED,
    val stopStage: String? = null,
    val models: List<ModelArtifact> = emptyList(),
    val activeModelId: String? = null,
    val download: DownloadProgress? = null,
    val error: String? = null,
    val configuredPort: Int = DEFAULT_SERVER_PORT,
    val serverPort: Int? = null,
    val backend: String? = null,
    val backendError: String? = null,
    val apiToken: String = "",
    val hasHuggingFaceToken: Boolean = false,
    val useGpu: Boolean = true
)

