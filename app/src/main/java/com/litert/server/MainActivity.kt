package com.litert.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.OpenableColumns
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.litert.server.DiagnosticsLogger
import com.litert.server.data.*
import com.litert.server.download.ModelArtifact
import com.litert.server.download.ModelDescriptor
import com.litert.server.download.ModelDownloadManager
import com.litert.server.service.LLMForegroundService
import com.litert.server.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var downloadManager: ModelDownloadManager
    private var appState by mutableStateOf(AppState())
    private var chatMessages = mutableStateListOf<ChatMessage>()
    private var isGenerating by mutableStateOf(false)
    private var visionResult by mutableStateOf("")
    private var isAnalyzing by mutableStateOf(false)
    private var selectedTab by mutableIntStateOf(0)
    private var availableModels by mutableStateOf(emptyList<ModelDescriptor>())
    private var installedModels by mutableStateOf(emptyList<ModelArtifact>())
    private var selectedModel by mutableStateOf<com.litert.server.download.ModelDescriptor?>(null)
    private var hasHuggingFaceToken by mutableStateOf(false)

    // Holds reference to the engine once the service boots it.
    // We bind to the service via a shared singleton so the UI can call it directly.
    private var liteRTEngine: com.litert.server.engine.LiteRTEngine? = null

    // ── File picker ───────────────────────────────────────────────────────
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        appState = appState.copy(status = AppStatus.INITIALIZING)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val displayName = displayNameFor(uri)
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")
                val model = downloadManager.importLocalModel(displayName, inputStream)
                withContext(Dispatchers.Main) {
                    stopEngineService()
                    refreshModels()
                    selectedModel = model
                    startEngineService()
                }
            } catch (e: Exception) {
                DiagnosticsLogger.error("MainActivity", "Model import failed", e)
                withContext(Dispatchers.Main) {
                    appState = appState.copy(
                        status = AppStatus.DOWNLOAD_ERROR,
                        errorMessage = "Failed to copy file: ${e.message}"
                    )
                }
            }
        }
    }

    private val engineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LLMForegroundService.ACTION_ENGINE_READY -> {
                    DiagnosticsLogger.event("MainActivity", "ENGINE_READY received")
                    val port = intent.getIntExtra(LLMForegroundService.EXTRA_SERVER_PORT, 8080)
                    val isGpu = intent.getBooleanExtra(LLMForegroundService.EXTRA_IS_GPU, true)
                    val apiToken = intent.getStringExtra(LLMForegroundService.EXTRA_API_TOKEN).orEmpty()
                    // Grab the engine reference from the service singleton
                    liteRTEngine = LLMForegroundService.engineInstance
                    appState = appState.copy(
                        status = AppStatus.READY,
                        isServerRunning = true,
                        serverPort = port,
                        isGpuBackend = isGpu,
                        engineReady = true,
                        apiToken = apiToken
                    )
                }
                LLMForegroundService.ACTION_ENGINE_ERROR -> {
                    val msg = intent.getStringExtra(LLMForegroundService.EXTRA_ERROR_MESSAGE)
                    DiagnosticsLogger.error("MainActivity", "ENGINE_ERROR received: ${msg ?: "missing message"}")
                    appState = appState.copy(status = AppStatus.ERROR, errorMessage = msg)
                }
            }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handle result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLogger.initialize(applicationContext)
        DiagnosticsLogger.event("MainActivity", "onCreate")

        downloadManager = ModelDownloadManager(this)
        refreshModels()

        val filter = IntentFilter().apply {
            addAction(LLMForegroundService.ACTION_ENGINE_READY)
            addAction(LLMForegroundService.ACTION_ENGINE_ERROR)
        }
        registerReceiver(engineReceiver, filter, RECEIVER_NOT_EXPORTED)

        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        checkModelAndUpdateState()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppContent()
            }
        }
    }

    @Composable
    fun AppContent() {
        MainTabLayout()
    }

    @Composable
    fun MainTabLayout() {
        val tabs = listOf("Models", "Chat", "Vision", "Server", "Settings")
        val icons = listOf(
            Icons.Default.Download,
            Icons.Default.Chat,
            Icons.Default.Image,
            Icons.Default.Api,
            Icons.Default.Settings
        )
        Scaffold(
            containerColor = DarkBackground,
            bottomBar = {
                NavigationBar(containerColor = SurfaceColor) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icons[index], contentDescription = tab) },
                            label = { Text(tab, color = if (selectedTab == index) GreenPrimary else Color.Gray) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = GreenPrimary,
                                unselectedIconColor = Color.Gray,
                                indicatorColor = Color(0xFF1A3A1A)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> DownloadScreen(
                        status = appState.status,
                        progressPercent = appState.downloadProgress,
                        downloadedMb = appState.downloadedMb,
                        totalMb = appState.totalMb,
                        speedMbps = appState.downloadSpeedMbps,
                        etaSeconds = appState.etaSeconds,
                        errorMessage = appState.errorMessage,
                        availableModels = availableModels,
                        installedModelIds = installedModels.mapTo(mutableSetOf()) { it.model.id },
                        selectedModel = selectedModel ?: downloadManager.getActiveModel(),
                        onModelSelected = ::selectModel,
                        onAddHuggingFaceModel = ::addHuggingFaceModel,
                        onNativeMaxTokensSaved = ::saveNativeMaxTokens,
                        hasHuggingFaceToken = hasHuggingFaceToken,
                        onSaveHuggingFaceToken = ::saveHuggingFaceToken,
                        onClearHuggingFaceToken = ::clearHuggingFaceToken,
                        onDownload = ::startDownload,
                        onRetry = { if (appState.status == AppStatus.ERROR) startEngineService() else startDownload() },
                        onPickFile = { pickFileLauncher.launch(arrayOf("*/*")) }
                    )
                    1 -> ChatScreen(
                        messages = chatMessages,
                        isGenerating = isGenerating,
                        onSend = ::sendMessage,
                        onClear = { chatMessages.clear() }
                    )
                    2 -> VisionScreen(
                        isAnalyzing = isAnalyzing,
                        analysisResult = visionResult,
                        onAnalyze = ::analyzeImage,
                        onShare = {
                            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("result", it))
                            Toast.makeText(this@MainActivity, "Copied!", Toast.LENGTH_SHORT).show()
                        }
                    )
                    3 -> ServerScreen(
                        isRunning = appState.isServerRunning,
                        port = appState.serverPort,
                        apiToken = appState.apiToken,
                        requestLog = appState.requestLog,
                        onToggle = ::toggleServer
                    )
                    4 -> SettingsScreen(
                        selectedModel = selectedModel ?: downloadManager.getActiveModel(),
                        installedModels = installedModels,
                        isGpu = appState.isGpuBackend,
                        hasHuggingFaceToken = hasHuggingFaceToken,
                        onSelectModel = ::selectModel,
                        onDeleteModel = ::deleteModel,
                        onDeleteAllModels = ::deleteAllModels,
                        onSaveHuggingFaceToken = ::saveHuggingFaceToken,
                        onClearHuggingFaceToken = ::clearHuggingFaceToken,
                        onCopyDiagnostics = ::copyDiagnostics,
                        onClearDiagnostics = ::clearDiagnostics
                    )
                }
            }
        }
    }

    // ── Chat ─────────────────────────────────────────────────────────────
    private fun sendMessage(text: String) {
        val engine = liteRTEngine
        if (engine == null || !engine.isReady) {
            Toast.makeText(this, "Engine not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val userMsg = ChatMessage(role = MessageRole.USER, content = text)
        chatMessages.add(userMsg)
        val prompt = buildChatPrompt(chatMessages)

        // Placeholder assistant bubble that streams tokens in
        val assistantMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        chatMessages.add(assistantMsg)
        val assistantIndex = chatMessages.lastIndex
        isGenerating = true

        lifecycleScope.launch {
            try {
                engine.generateText(prompt)
                    .onCompletion { err ->
                        isGenerating = false
                        chatMessages[assistantIndex] =
                            chatMessages[assistantIndex].copy(isStreaming = false)
                        if (err != null) {
                            chatMessages[assistantIndex] =
                                chatMessages[assistantIndex].copy(content = "Error: ${err.message}")
                        }
                    }
                    .collect { token ->
                        chatMessages[assistantIndex] = chatMessages[assistantIndex].copy(
                            content = chatMessages[assistantIndex].content + token
                        )
                    }
            } catch (e: Exception) {
                DiagnosticsLogger.error("MainActivity", "Chat generation failed", e)
                isGenerating = false
                chatMessages[assistantIndex] =
                    chatMessages[assistantIndex].copy(
                        content = "Error: ${e.message}",
                        isStreaming = false
                    )
            }
        }
    }

    private fun buildChatPrompt(messages: List<ChatMessage>): String = buildString {
        append("Continue this conversation. Answer the last user message.\n\n")
        messages.forEach { message ->
            if (message.content.isBlank()) return@forEach
            when (message.role) {
                MessageRole.USER -> append("User: ")
                MessageRole.ASSISTANT -> append("Assistant: ")
            }
            append(message.content).append('\n')
        }
        append("Assistant: ")
    }

    // ── Vision ───────────────────────────────────────────────────────────
    private fun analyzeImage(uri: Uri, prompt: String) {
        val engine = liteRTEngine
        if (engine == null || !engine.isReady) {
            Toast.makeText(this, "Engine not ready", Toast.LENGTH_SHORT).show()
            return
        }

        isAnalyzing = true
        visionResult = ""

        lifecycleScope.launch {
            try {
                // Copy URI to a temp file so LiteRT can read it as a file path
                val tmpFile = File(cacheDir, "vision_input_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        tmpFile.outputStream().use { out -> ins.copyTo(out) }
                    }
                }

                engine.analyzeImage(tmpFile.absolutePath, prompt)
                    .onCompletion {
                        isAnalyzing = false
                        tmpFile.delete()
                    }
                    .collect { token ->
                        visionResult += token
                    }
            } catch (e: Exception) {
                DiagnosticsLogger.error("MainActivity", "Vision analysis failed", e)
                isAnalyzing = false
                visionResult = "Error: ${e.message}"
            }
        }
    }

    // ── Lifecycle helpers ───────────────────────────────────────────────
    private fun refreshModels() {
        availableModels = downloadManager.getAvailableModels()
        installedModels = downloadManager.getInstalledModels()
        selectedModel = downloadManager.getActiveModel()
        hasHuggingFaceToken = downloadManager.hasHuggingFaceToken()
    }

    private fun selectModel(model: ModelDescriptor) {
        val changed = selectedModel?.id != model.id
        if (changed) stopEngineService()
        downloadManager.setModel(model)
        refreshModels()
        checkModelAndUpdateState()
    }

    private fun addHuggingFaceModel(input: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val model = downloadManager.addCustomHuggingFaceModel(input)
                withContext(Dispatchers.Main) {
                    stopEngineService()
                    refreshModels()
                    selectedModel = model
                    appState = appState.copy(status = AppStatus.MODEL_NOT_FOUND, errorMessage = null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appState = appState.copy(
                        status = AppStatus.DOWNLOAD_ERROR,
                        errorMessage = e.message ?: "Could not add Hugging Face model"
                    )
                }
            }
        }
    }

    private fun saveNativeMaxTokens(nativeMaxTokens: Int) {
        val model = selectedModel ?: downloadManager.getActiveModel()
        try {
            val updated = downloadManager.setNativeMaxTokens(model.id, nativeMaxTokens)
            val shouldRestart = appState.isServerRunning && downloadManager.isModelDownloaded()
            if (shouldRestart) stopEngineService()
            refreshModels()
            selectedModel = updated
            if (shouldRestart) startEngineService()
            Toast.makeText(this, "Native token limit saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Invalid native token limit", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteModel(model: ModelDescriptor) {
        val deletingActiveModel = selectedModel?.id == model.id
        if (deletingActiveModel) stopEngineService()
        downloadManager.deleteModel(model.id)
        refreshModels()
        checkModelAndUpdateState()
    }

    private fun deleteAllModels() {
        stopEngineService()
        downloadManager.deleteAllModels()
        refreshModels()
        appState = appState.copy(status = AppStatus.MODEL_NOT_FOUND, errorMessage = null)
    }

    private fun checkModelAndUpdateState() {
        refreshModels()
        if (downloadManager.isModelDownloaded()) {
            startEngineService()
        } else {
            appState = appState.copy(status = AppStatus.MODEL_NOT_FOUND, isServerRunning = false, engineReady = false)
        }
    }

    private fun saveHuggingFaceToken(token: String) {
        try {
            downloadManager.setHuggingFaceToken(token)
            refreshModels()
            Toast.makeText(this, "Hugging Face token saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Invalid Hugging Face token", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearHuggingFaceToken() {
        downloadManager.clearHuggingFaceToken()
        refreshModels()
        Toast.makeText(this, "Hugging Face token cleared", Toast.LENGTH_SHORT).show()
    }

    private fun startDownload() {
        appState = appState.copy(status = AppStatus.DOWNLOADING, errorMessage = null)
        lifecycleScope.launch(Dispatchers.IO) {
            downloadManager.downloadModel()
                .catch { e ->
                    DiagnosticsLogger.error("MainActivity", "Model download failed", e)
                    withContext(Dispatchers.Main) {
                        appState = appState.copy(
                            status = AppStatus.DOWNLOAD_ERROR,
                            errorMessage = e.message ?: e.javaClass.simpleName
                        )
                    }
                }
                .collect { progress ->
                    withContext(Dispatchers.Main) {
                        appState = appState.copy(
                            downloadProgress = progress.progressPercent,
                            downloadedMb = progress.downloadedMb,
                            totalMb = progress.totalMb,
                            downloadSpeedMbps = progress.speedMbps,
                            etaSeconds = progress.etaSeconds
                        )
                        if (progress.isDone) {

                            refreshModels()
                            startEngineService()
                        }
                    }
                }
        }
    }

    private fun copyDiagnostics() {
        val text = DiagnosticsLogger.readLog(this)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("litert-diagnostics", text))
        Toast.makeText(this, "Copied diagnostics log", Toast.LENGTH_SHORT).show()
    }

    private fun clearDiagnostics() {
        DiagnosticsLogger.clear(this)
        Toast.makeText(this, "Diagnostics log cleared", Toast.LENGTH_SHORT).show()
    }

    private fun startEngineService() {
        if (!downloadManager.isModelDownloaded()) {
            selectedTab = 0
            appState = appState.copy(status = AppStatus.MODEL_NOT_FOUND, isServerRunning = false, engineReady = false)
            Toast.makeText(this, "Download or import a model first", Toast.LENGTH_SHORT).show()
            return
        }

        val activeModel = downloadManager.getActiveModel()
        DiagnosticsLogger.event("MainActivity", "Starting engine service nativeMaxTokens=${activeModel.nativeMaxTokens}")
        appState = appState.copy(status = AppStatus.INITIALIZING)
        val intent = Intent(this, LLMForegroundService::class.java).apply {
            putExtra(LLMForegroundService.EXTRA_MODEL_PATH, downloadManager.getModelPath())
            putExtra(LLMForegroundService.EXTRA_MODEL_ID, activeModel.id)
            putExtra(LLMForegroundService.EXTRA_MODEL_DISPLAY_NAME, activeModel.displayName)
            putExtra(LLMForegroundService.EXTRA_NATIVE_MAX_TOKENS, activeModel.nativeMaxTokens)
            putExtra(LLMForegroundService.EXTRA_USE_GPU, true)
        }
        startForegroundService(intent)
    }

    private fun stopEngineService() {
        DiagnosticsLogger.event("MainActivity", "Stopping engine service")
        stopService(Intent(this, LLMForegroundService::class.java))
        liteRTEngine = null
        appState = appState.copy(isServerRunning = false, engineReady = false, apiToken = "")
    }

    private fun toggleServer() {
        if (appState.isServerRunning) {
            stopEngineService()
        } else {
            startEngineService()
        }
    }


    private fun displayNameFor(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "local-model.litertlm"
    }
    override fun onDestroy() {
        unregisterReceiver(engineReceiver)
        super.onDestroy()
    }
}
