package com.litert.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.litert.server.data.RequestLogEntry
import com.litert.server.DiagnosticsLogger
import com.litert.server.engine.LiteRTEngine
import kotlinx.coroutines.CoroutineScope
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LLMForegroundService : Service() {

    companion object {
        private const val TAG = "LLMForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "litert_server_channel"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_DISPLAY_NAME = "model_display_name"
        const val EXTRA_USE_GPU = "use_gpu"
        const val ACTION_ENGINE_READY = "com.litert.server.ENGINE_READY"
        const val ACTION_ENGINE_ERROR = "com.litert.server.ENGINE_ERROR"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_IS_GPU = "is_gpu"
        const val EXTRA_API_TOKEN = "api_token"

        /**
         * Shared engine reference so MainActivity can call it directly for in-app chat/vision.
         * Set when engine is ready, cleared on service destroy.
         */
        @Volatile
        var engineInstance: LiteRTEngine? = null
            private set
    }

        private fun maxNumTokensForModel(modelId: String): Int =
            when (modelId) {
                "gemma-4-e2b-it" -> 128_000
                "gemma-4-e4b-it" -> 32_000
                else -> 32_000
            }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var llmEngine: LiteRTEngine? = null
    private var apiServer: HttpApiServer? = null
    private val apiToken: String = generateApiToken()
    private var initializationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.initialize(applicationContext)
        DiagnosticsLogger.event(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH) ?: return START_NOT_STICKY
        val useGpu = intent.getBooleanExtra(EXTRA_USE_GPU, true)
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: "local-litertlm"
        val modelDisplayName = intent.getStringExtra(EXTRA_MODEL_DISPLAY_NAME) ?: modelId

        startAsForeground()
        val maxNumTokens = maxNumTokensForModel(modelId)
        DiagnosticsLogger.event(
            TAG,
            "onStartCommand modelId=$modelId displayName=$modelDisplayName useGpu=$useGpu maxNumTokens=$maxNumTokens path=$modelPath"
        )

        initializationJob?.cancel()
        initializationJob = scope.launch {
            try {
                engineInstance = null
                apiServer?.stop()
                apiServer = null
                llmEngine?.shutdown()
                llmEngine = null

                val engine = LiteRTEngine(applicationContext)
                llmEngine = engine

                val success = engine.initialize(modelPath, useGpu, maxTokens = maxNumTokens)
                if (!success) {
                    broadcastError(engine.getLastInitializationError() ?: "Failed to initialize LLM engine")
                    return@launch
                }

                val requestLog = mutableListOf<RequestLogEntry>()
                val server = HttpApiServer(engine, apiToken, modelId) { entry ->
                    synchronized(requestLog) { requestLog.add(entry) }
                }
                val port = server.start()
                apiServer = server

                // Expose engine to MainActivity before broadcasting ready
                engineInstance = engine

                updateNotification("LiteRT Server Running — $modelDisplayName on localhost:$port")
                broadcastReady(port, engine.getBackend() == "GPU")
            } catch (t: Throwable) {
                DiagnosticsLogger.error(TAG, "Service error", t)
                broadcastError(t.message ?: "Unknown service error: ${t.javaClass.name}")
            }
        }

        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("LiteRT Server — Starting...")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LiteRT Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "LLM inference and HTTP API server"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiteRT Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastReady(port: Int, isGpu: Boolean) {
        val intent = Intent(ACTION_ENGINE_READY).apply {
            putExtra(EXTRA_SERVER_PORT, port)
            putExtra(EXTRA_IS_GPU, isGpu)
            putExtra(EXTRA_API_TOKEN, apiToken)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_ENGINE_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun generateApiToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun onDestroy() {
        DiagnosticsLogger.event(TAG, "Service destroy")
        initializationJob?.cancel()
        initializationJob = null
        engineInstance = null
        apiServer?.stop()
        apiServer = null
        llmEngine?.shutdown()
        llmEngine = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
