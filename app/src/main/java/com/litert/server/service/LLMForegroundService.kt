package com.litert.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.litert.server.data.ServerSnapshot
import com.litert.server.data.ServerStatus
import com.litert.server.download.ModelArtifact
import com.litert.server.download.ModelDescriptor
import com.litert.server.download.ModelDownloadManager
import com.litert.server.engine.LiteRTEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.coroutineContext

class LLMForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "litert_server_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "litert_server_service"
        private const val PREF_API_TOKEN = "api_token"
        private const val PREF_USE_GPU = "use_gpu"

        private const val ACTION_REFRESH = "com.litert.server.action.REFRESH"
        private const val ACTION_SELECT_MODEL = "com.litert.server.action.SELECT_MODEL"
        private const val ACTION_SET_NATIVE_MAX_TOKENS =
            "com.litert.server.action.SET_NATIVE_MAX_TOKENS"
        private const val ACTION_ADD_AND_DOWNLOAD = "com.litert.server.action.ADD_AND_DOWNLOAD"
        private const val ACTION_DOWNLOAD_SELECTED =
            "com.litert.server.action.DOWNLOAD_SELECTED"
        private const val ACTION_CANCEL_DOWNLOAD = "com.litert.server.action.CANCEL_DOWNLOAD"
        private const val ACTION_START_SERVER = "com.litert.server.action.START_SERVER"
        private const val ACTION_STOP_SERVER = "com.litert.server.action.STOP_SERVER"
        private const val ACTION_DELETE_MODEL = "com.litert.server.action.DELETE_MODEL"
        private const val ACTION_SET_HF_TOKEN = "com.litert.server.action.SET_HF_TOKEN"

        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_NATIVE_MAX_TOKENS = "native_max_tokens"
        private const val EXTRA_DIRECT_URL = "direct_url"
        private const val EXTRA_USE_GPU = "use_gpu"
        private const val EXTRA_HF_TOKEN = "hf_token"

        private val mutableState = MutableStateFlow(ServerSnapshot())
        val state: StateFlow<ServerSnapshot> = mutableState.asStateFlow()

        fun refresh(context: Context) {
            dispatch(context, ACTION_REFRESH)
        }

        fun selectModel(context: Context, modelId: String) {
            dispatch(context, ACTION_SELECT_MODEL) {
                putExtra(EXTRA_MODEL_ID, modelId)
            }
        }

        fun setNativeMaxTokens(context: Context, modelId: String, value: Int) {
            dispatch(context, ACTION_SET_NATIVE_MAX_TOKENS) {
                putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_NATIVE_MAX_TOKENS, value)
            }
        }

        fun setHuggingFaceToken(context: Context, token: String?) {
            dispatch(context, ACTION_SET_HF_TOKEN) {
                putExtra(EXTRA_HF_TOKEN, token)
            }
        }

        fun addAndDownload(context: Context, directUrl: String) {
            dispatch(context, ACTION_ADD_AND_DOWNLOAD, foreground = true) {
                putExtra(EXTRA_DIRECT_URL, directUrl)
            }
        }

        fun downloadSelected(context: Context) {
            dispatch(context, ACTION_DOWNLOAD_SELECTED, foreground = true)
        }

        fun cancelDownload(context: Context) {
            dispatch(context, ACTION_CANCEL_DOWNLOAD)
        }

        fun startServer(context: Context, useGpu: Boolean) {
            dispatch(context, ACTION_START_SERVER, foreground = true) {
                putExtra(EXTRA_USE_GPU, useGpu)
            }
        }

        fun stopServer(context: Context) {
            dispatch(context, ACTION_STOP_SERVER)
        }

        fun deleteModel(context: Context, modelId: String) {
            dispatch(context, ACTION_DELETE_MODEL) {
                putExtra(EXTRA_MODEL_ID, modelId)
            }
        }

        private fun dispatch(
            context: Context,
            action: String,
            foreground: Boolean = false,
            configure: Intent.() -> Unit = {}
        ) {
            val intent = Intent(context, LLMForegroundService::class.java)
                .setAction(action)
                .apply(configure)
            if (foreground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var manager: ModelDownloadManager
    private lateinit var apiToken: String
    private var operationJob: Job? = null
    private var engine: LiteRTEngine? = null
    private var apiServer: HttpApiServer? = null
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        manager = ModelDownloadManager(applicationContext)
        apiToken = getOrCreateApiToken()
        mutableState.update {
            it.copy(
                apiToken = apiToken,
                useGpu = preferences.getBoolean(PREF_USE_GPU, true)
            )
        }
        createNotificationChannel()
        refreshModelSnapshot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: Intent().setAction(ACTION_REFRESH)
        when (command.action ?: ACTION_REFRESH) {
            ACTION_REFRESH -> serviceScope.launch {
                if (operationJob?.isActive != true) refreshModelSnapshot()
            }
            ACTION_SELECT_MODEL -> serviceScope.launch {
                handleSelectModel(command.getStringExtra(EXTRA_MODEL_ID))
            }
            ACTION_SET_NATIVE_MAX_TOKENS -> serviceScope.launch {
                handleSetNativeMaxTokens(
                    command.getStringExtra(EXTRA_MODEL_ID),
                    command.getIntExtra(EXTRA_NATIVE_MAX_TOKENS, -1)
                )
            }
            ACTION_SET_HF_TOKEN -> serviceScope.launch {
                handleSetHuggingFaceToken(command.getStringExtra(EXTRA_HF_TOKEN))
            }
            ACTION_ADD_AND_DOWNLOAD -> {
                if (!isServerOrInitializationBusy() && operationJob?.isActive != true) {
                    startAsForeground(
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        "Preparing model download…"
                    )
                }
                serviceScope.launch {
                    handleAddAndDownload(command.getStringExtra(EXTRA_DIRECT_URL))
                }
            }
            ACTION_DOWNLOAD_SELECTED -> {
                if (!isServerOrInitializationBusy() && operationJob?.isActive != true) {
                    startAsForeground(
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        "Preparing model download…"
                    )
                }
                serviceScope.launch { handleDownloadSelected() }
            }
            ACTION_CANCEL_DOWNLOAD -> serviceScope.launch { handleCancelDownload() }
            ACTION_START_SERVER -> {
                if (!isDownloadBusy() && operationJob?.isActive != true) {
                    startAsForeground(
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                        "Starting LiteRT server…"
                    )
                }
                serviceScope.launch {
                    handleStartServer(command.getBooleanExtra(EXTRA_USE_GPU, true))
                }
            }
            ACTION_STOP_SERVER -> serviceScope.launch { handleStopServer() }
            ACTION_DELETE_MODEL -> serviceScope.launch {
                handleDeleteModel(command.getStringExtra(EXTRA_MODEL_ID))
            }
            else -> serviceScope.launch {
                if (operationJob?.isActive != true) refreshModelSnapshot()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun handleSelectModel(modelId: String?) {
        if (modelId.isNullOrBlank()) {
            setCommandError("Select a model before continuing")
            return
        }
        if (!modelMutationAllowed()) return
        try {
            manager.selectModel(modelId)
            refreshModelSnapshot()
            mutableState.update { it.copy(download = null) }
            clearCommandError()
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to select model"))
        }
    }

    private suspend fun handleSetNativeMaxTokens(modelId: String?, value: Int) {
        if (modelId.isNullOrBlank() || value <= 0) {
            setCommandError("Choose a valid native context size")
            return
        }
        if (!modelMutationAllowed()) return
        try {
            manager.setNativeMaxTokens(modelId, value)
            refreshModelSnapshot()
            clearCommandError()
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to save native context size"))
        }
    }

    private suspend fun handleSetHuggingFaceToken(token: String?) {
        try {
            withContext(Dispatchers.IO) {
                manager.setHuggingFaceToken(token)
            }
            refreshModelSnapshot()
            clearCommandError()
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to save Hugging Face token"))
        }
    }

    private suspend fun handleAddAndDownload(directUrl: String?) {
        if (!beginDownloadOperation()) return
        val url = directUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            setCommandError("Enter a direct Hugging Face .litertlm URL")
            finishRejectedOperation()
            return
        }
        launchOperation {
            try {
                val model = withContext(Dispatchers.IO) {
                    manager.addCustomModel(url)
                }
                mutableState.update {
                    it.copy(
                        activeModelId = model.id,
                        error = null,
                        download = null
                    )
                }
                runDownload(model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                setOperationError(errorMessage(t, "Unable to add model"))
                stopForegroundIfIdle()
            }
        }
    }

    private suspend fun handleDownloadSelected() {
        if (!beginDownloadOperation()) return
        val model = try {
            manager.getActiveModel()
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to read selected model"))
            finishRejectedOperation()
            return
        }
        mutableState.update {
            it.copy(
                status = ServerStatus.DOWNLOADING,
                activeModelId = model.id,
                download = null,
                error = null,
                serverPort = null,
                backend = null
            )
        }
        launchOperation { runDownload(model) }
    }

    private suspend fun runDownload(model: ModelDescriptor) {
        try {
            withContext(Dispatchers.IO) {
                manager.downloadModel(model).collect { progress ->
                    mutableState.update { current ->
                        current.copy(
                            status = ServerStatus.DOWNLOADING,
                            activeModelId = model.id,
                            download = progress,
                            error = progress.error
                        )
                    }
                }
            }
            mutableState.update {
                it.copy(
                    status = ServerStatus.STOPPED,
                    activeModelId = model.id,
                    download = null,
                    error = null
                )
            }
            refreshModelSnapshot()
        } catch (cancelled: CancellationException) {
            mutableState.update {
                if (it.status == ServerStatus.DOWNLOADING) {
                    it.copy(status = ServerStatus.STOPPED, download = null)
                } else {
                    it
                }
            }
            if (!destroying) refreshModelSnapshot()
            throw cancelled
        } catch (t: Throwable) {
            setOperationError(errorMessage(t, "Model download failed"))
            refreshModelSnapshot()
        } finally {
            stopForegroundIfIdle()
        }
    }

    private suspend fun handleStartServer(useGpu: Boolean) {
        if (!beginServerOperation()) return
        val model = try {
            manager.getActiveModel()
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to read selected model"))
            finishRejectedOperation()
            return
        }
        val artifact = try {
            manager.getModelArtifacts().firstOrNull { it.model.id == model.id }
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to inspect selected model"))
            finishRejectedOperation()
            return
        }
        if (artifact == null || !artifact.isInstalled) {
            setCommandError("Download ${model.displayName} before starting the server")
            finishRejectedOperation()
            return
        }
        preferences.edit().putBoolean(PREF_USE_GPU, useGpu).apply()
        mutableState.update {
            it.copy(
                status = ServerStatus.INITIALIZING,
                activeModelId = model.id,
                error = null,
                serverPort = null,
                backend = null,
                backendError = null,
                useGpu = useGpu
            )
        }
        launchOperation { runServer(model, artifact, useGpu) }
    }

    private suspend fun runServer(
        model: ModelDescriptor,
        artifact: ModelArtifact,
        useGpu: Boolean
    ) {
        var failure: Throwable? = null
        val localEngine = LiteRTEngine(applicationContext)
        engine = localEngine
        try {
            val initialized = withContext(Dispatchers.IO) {
                localEngine.initialize(artifact.path, useGpu, model.nativeMaxTokens)
            }
            if (initialized.isFailure) {
                throw initialized.exceptionOrNull()
                    ?: IllegalStateException("LiteRT engine initialization failed")
            }
            val localServer = HttpApiServer(localEngine, apiToken, model.id)
            apiServer = localServer
            val port = withContext(Dispatchers.IO) { localServer.start() }
            mutableState.update {
                it.copy(
                    status = ServerStatus.RUNNING,
                    activeModelId = model.id,
                    serverPort = port,
                    backend = localEngine.backend,
                    backendError = localEngine.gpuFallbackReason,
                    error = null,
                    useGpu = useGpu
                )
            }
            updateNotification(
                "LiteRT Server running on 127.0.0.1:$port (${localEngine.backend})"
            )
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            failure = t
            setOperationError(errorMessage(t, "Unable to start LiteRT server"))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val server = apiServer
                apiServer = null
                try {
                    server?.stop()
                } catch (_: Throwable) {
                }
                val activeEngine = engine
                engine = null
                try {
                    activeEngine?.shutdown()
                } catch (_: Throwable) {
                }
            }
            if (failure == null && !destroying) {
                mutableState.update {
                    it.copy(
                        status = ServerStatus.STOPPED,
                        serverPort = null,
                        backendError = null,
                        backend = null
                    )
                }
            }
            if (!destroying) stopForegroundIfIdle()
        }
    }

    private suspend fun handleCancelDownload() {
        if (mutableState.value.status != ServerStatus.DOWNLOADING) return
        operationJob?.cancel()
    }

    private suspend fun handleStopServer() {
        when (mutableState.value.status) {
            ServerStatus.INITIALIZING,
            ServerStatus.RUNNING -> operationJob?.cancel()
            ServerStatus.ERROR -> {
                if (operationJob?.isActive != true) {
                    mutableState.update {
                        it.copy(
                            status = ServerStatus.STOPPED,
                            error = null,
                            serverPort = null,
                            backend = null,
                            backendError = null
                        )
                    }
                    stopForegroundIfIdle()
                }
            }
            ServerStatus.DOWNLOADING -> setCommandError(
                "Stop the model download before stopping the server"
            )
            ServerStatus.STOPPED -> Unit
        }
    }

    private suspend fun handleDeleteModel(modelId: String?) {
        if (modelId.isNullOrBlank()) {
            setCommandError("Choose a model to delete")
            return
        }
        if (!modelMutationAllowed()) return
        try {
            manager.deleteModel(modelId)
            refreshModelSnapshot()
            mutableState.update { it.copy(download = null) }
            clearCommandError()
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to delete model"))
        }
    }

    private fun refreshModelSnapshot() {
        try {
            val models = manager.getModelArtifacts()
            val active = manager.getActiveModel()
            mutableState.update {
                it.copy(
                    models = models,
                    activeModelId = active.id,
                    apiToken = apiToken,
                    useGpu = preferences.getBoolean(PREF_USE_GPU, true),
                    hasHuggingFaceToken = manager.hasHuggingFaceToken(),
                )
            }
        } catch (t: Throwable) {
            setCommandError(errorMessage(t, "Unable to read model state"))
        }
    }

    private fun beginDownloadOperation(): Boolean {
        if (mutableState.value.status == ServerStatus.RUNNING ||
            mutableState.value.status == ServerStatus.INITIALIZING ||
            operationJob?.isActive == true
        ) {
            setCommandError("Stop the server before changing or downloading models")
            return false
        }
        mutableState.update {
            it.copy(
                status = ServerStatus.DOWNLOADING,
                download = null,
                error = null,
                serverPort = null,
                backend = null,
                backendError = null
            )
        }
        return true
    }

    private fun beginServerOperation(): Boolean {
        if (mutableState.value.status == ServerStatus.DOWNLOADING ||
            operationJob?.isActive == true
        ) {
            setCommandError("Wait for the model download to finish before starting")
            return false
        }
        if (mutableState.value.status == ServerStatus.RUNNING ||
            mutableState.value.status == ServerStatus.INITIALIZING
        ) {
            setCommandError("The LiteRT server is already running")
            return false
        }
        return true
    }

    private fun modelMutationAllowed(): Boolean {
        if (mutableState.value.status == ServerStatus.DOWNLOADING ||
            mutableState.value.status == ServerStatus.INITIALIZING ||
            mutableState.value.status == ServerStatus.RUNNING ||
            operationJob?.isActive == true
        ) {
            setCommandError("Stop the server and wait for downloads before changing models")
            return false
        }
        return true
    }

    private fun isServerOrInitializationBusy(): Boolean =
        mutableState.value.status == ServerStatus.RUNNING ||
            mutableState.value.status == ServerStatus.INITIALIZING

    private fun isDownloadBusy(): Boolean =
        mutableState.value.status == ServerStatus.DOWNLOADING

    private fun finishRejectedOperation() {
        operationJob = null
        if (mutableState.value.status == ServerStatus.DOWNLOADING ||
            mutableState.value.status == ServerStatus.INITIALIZING
        ) {
            mutableState.update { it.copy(status = ServerStatus.ERROR) }
        }
        stopForegroundIfIdle()
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (operationJob?.isActive == true) return
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (operationJob === coroutineContext[Job]) operationJob = null
            }
        }
        operationJob = job
        job.start()
    }

    private fun setCommandError(message: String) {
        mutableState.update {
            if (it.status == ServerStatus.STOPPED) {
                it.copy(status = ServerStatus.ERROR, error = message)
            } else {
                it.copy(error = message)
            }
        }
    }

    private fun clearCommandError() {
        mutableState.update {
            if (it.status == ServerStatus.ERROR) {
                it.copy(status = ServerStatus.STOPPED, error = null)
            } else {
                it.copy(error = null)
            }
        }
    }

    private fun setOperationError(message: String) {
        mutableState.update {
            it.copy(
                status = ServerStatus.ERROR,
                error = message,
                serverPort = null,
                backendError = null,
                backend = null
            )
        }
    }

    private fun startAsForeground(type: Int, text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            type
        )
    }

    private fun stopForegroundIfIdle() {
        if (mutableState.value.status != ServerStatus.DOWNLOADING &&
            mutableState.value.status != ServerStatus.INITIALIZING &&
            mutableState.value.status != ServerStatus.RUNNING
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LiteRT Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Local LiteRT model download and inference server"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiteRT Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun getOrCreateApiToken(): String {
        preferences.getString(PREF_API_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also {
            preferences.edit().putString(PREF_API_TOKEN, it).apply()
        }
    }

    private fun errorMessage(t: Throwable, fallback: String): String =
        generateSequence(t) { it.cause }
            .map { cause ->
                val detail = cause.message?.trim()?.takeIf { it.isNotEmpty() }
                if (detail != null) "${cause.javaClass.simpleName}: $detail"
                else cause.javaClass.simpleName
            }
            .distinct()
            .joinToString(" → ")
            .ifBlank { fallback }
            .take(2_000)

    override fun onDestroy() {
        destroying = true
        operationJob?.cancel()
        mutableState.update {
            it.copy(
                status = ServerStatus.STOPPED,
                download = null,
                serverPort = null,
                backend = null,
                backendError = null
            )
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
