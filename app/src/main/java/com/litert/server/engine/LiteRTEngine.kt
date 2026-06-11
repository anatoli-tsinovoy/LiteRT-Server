package com.litert.server.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LiteRTEngine(private val context: Context) {

    companion object {
        private const val TAG = "LiteRTEngine"
        private const val MIN_EXPECTED_MODEL_BYTES = 100_000_000L
    }

    private var engine: Engine? = null
    private var lastInitializationError: String? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentBackend: String = "GPU"
    private var currentSamplerConfig: SamplerConfig = SamplerConfig(
        topK = 40,
        topP = 0.9,
        temperature = 0.7
    )

    var isReady = false
        private set

    suspend fun initialize(
        modelPath: String,
        useGpu: Boolean = true,
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
        topK: Int = 40,
        topP: Double = 0.9
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val modelFile = java.io.File(modelPath)
            val modelDetails = modelFile.diagnosticSummary()
            try {
                lastInitializationError = null
                if (!modelFile.isFile) {
                    throw IllegalArgumentException("Model file does not exist or is not a regular file: $modelDetails")
                }
                if (!modelFile.canRead()) {
                    throw IllegalArgumentException("Model file is not readable: $modelDetails")
                }
                if (modelFile.length() < MIN_EXPECTED_MODEL_BYTES) {
                    throw IllegalArgumentException("Model file is too small to be a valid .litertlm artifact: $modelDetails")
                }

                val backend = if (useGpu) Backend.GPU() else Backend.CPU()

                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath
                )
                val newEngine = Engine(config)
                newEngine.initialize()

                currentSamplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature)
                val conv = createNewConversation(newEngine, currentSamplerConfig)

                engine = newEngine
                conversation = conv
                currentBackend = if (useGpu) "GPU" else "CPU"
                isReady = true
                Log.i(TAG, "Engine initialized with $currentBackend backend for $modelDetails")
                true
            } catch (t: Throwable) {
                val backendName = if (useGpu) "GPU" else "CPU"
                val diagnostic = buildInitializationDiagnostic(backendName, modelDetails, t)
                Log.e(TAG, diagnostic, t)
                if (useGpu) {
                    Log.w(TAG, "Falling back to CPU backend after GPU initialization failure")
                    initialize(modelPath, useGpu = false, temperature, maxTokens, topK, topP)
                } else {
                    lastInitializationError = diagnostic
                    isReady = false
                    false
                }
            }
        }
    }

    fun getLastInitializationError(): String? = lastInitializationError

    private fun java.io.File.diagnosticSummary(): String =
        "path=$absolutePath, exists=${exists()}, isFile=$isFile, canRead=${canRead()}, " +
            "bytes=${if (exists()) length() else 0}, usableSpace=${parentFile?.usableSpace ?: 0}, " +
            "sdk=${android.os.Build.VERSION.SDK_INT}, device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "abi=${android.os.Build.SUPPORTED_ABIS.joinToString()}"

    private fun buildInitializationDiagnostic(
        backendName: String,
        modelDetails: String,
        throwable: Throwable
    ): String {
        val root = generateSequence(throwable) { it.cause }.last()
        return "Failed to initialize LiteRT-LM engine with $backendName backend. " +
            "Model diagnostics: $modelDetails. " +
            "Error: ${throwable.javaClass.name}: ${throwable.message ?: "no message"}. " +
            "Root cause: ${root.javaClass.name}: ${root.message ?: "no message"}"
    }

    private fun createNewConversation(
        eng: Engine,
        samplerConfig: SamplerConfig
    ): com.google.ai.edge.litertlm.Conversation {
        return eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    Content.Text(
                        "You are a helpful AI assistant running locally on an Android device " +
                        "powered by Google's Gemma multimodal LLM via LiteRT."
                    )
                ),
                samplerConfig = samplerConfig
            )
        )
    }

    suspend fun generateText(prompt: String): Flow<String> {
        val conv = conversation ?: throw IllegalStateException("Engine not initialized")
        return conv.sendMessageAsync(prompt).map { it.toString() }
    }

    /**
     * Passes the image file + prompt as proper multimodal content.
     * imagePath must be an absolute file path readable by the engine.
     */
    suspend fun analyzeImage(imagePath: String, prompt: String): Flow<String> {
        val conv = conversation ?: throw IllegalStateException("Engine not initialized")
        val contents = Contents.of(
            Content.ImageFile(imagePath),
            Content.Text(prompt)
        )
        return conv.sendMessageAsync(contents).map { it.toString() }
    }

    fun clearHistory() {
        val eng = engine ?: return
        conversation?.close()
        conversation = createNewConversation(eng, currentSamplerConfig)
        Log.i(TAG, "Conversation history cleared")
    }

    fun getBackend(): String = currentBackend

    fun shutdown() {
        isReady = false
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
