package com.litert.server.engine

import android.content.Context
import com.litert.server.DiagnosticsLogger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


data class GenerationUsage(
    val promptTokens: Int,
    val completionTokens: Int
) {
    val totalTokens: Int
        get() = promptTokens + completionTokens
}

class LiteRTEngine(private val context: Context) {

    companion object {
        private const val TAG = "LiteRTEngine"
        private const val MIN_EXPECTED_MODEL_BYTES = 100_000_000L
    }

    private val sdkMutex = Mutex()
    private var engine: Engine? = null
    private var lastInitializationError: String? = null
    private var currentBackend: String = "CPU"
    private var currentSamplerConfig: SamplerConfig = SamplerConfig(
        topK = 40,
        topP = 0.9,
        temperature = 0.7
    )

    @Volatile
    private var lastGenerationUsage: GenerationUsage? = null

    @Volatile
    var isReady = false
        private set

    suspend fun initialize(
        modelPath: String,
        useGpu: Boolean = true,
        temperature: Double = 0.7,
        maxTokens: Int = 32_000,
        topK: Int = 40,
        topP: Double = 0.9
    ): Boolean = withContext(Dispatchers.IO) {
        sdkMutex.withLock {
            DiagnosticsLogger.event(
                TAG,
                "initialize start useGpu=$useGpu maxTokens=$maxTokens topK=$topK topP=$topP temperature=$temperature"
            )
            closeLocked()
            lastInitializationError = null
            isReady = false

            val modelFile = java.io.File(modelPath)
            val modelDetails = modelFile.diagnosticSummary()
            try {
                validateModelFile(modelFile, modelDetails)
                currentSamplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature)

                if (useGpu) {
                    val gpuError = tryInitializeLocked(
                        modelPath = modelPath,
                        modelDetails = modelDetails,
                        backend = Backend.GPU(),
                        backendName = "GPU",
                        maxTokens = maxTokens
                    )
                    if (gpuError == null) return@withLock true

                    DiagnosticsLogger.warn(TAG, "Falling back to CPU backend after GPU initialization failure")
                }

                val cpuError = tryInitializeLocked(
                    modelPath = modelPath,
                    modelDetails = modelDetails,
                    backend = Backend.CPU(),
                    backendName = "CPU",
                    maxTokens = maxTokens
                )
                if (cpuError == null) {
                    true
                } else {
                    lastInitializationError = cpuError
                    false
                }
            } catch (t: Throwable) {
                val diagnostic = buildInitializationDiagnostic("preflight", modelDetails, t)
                DiagnosticsLogger.error(TAG, diagnostic, t)
                lastInitializationError = diagnostic
                false
            }
        }
    }

    fun getLastInitializationError(): String? = lastInitializationError

    private fun validateModelFile(modelFile: java.io.File, modelDetails: String) {
        if (!modelFile.isFile) {
            throw IllegalArgumentException("Model file does not exist or is not a regular file: $modelDetails")
        }
        if (!modelFile.canRead()) {
            throw IllegalArgumentException("Model file is not readable: $modelDetails")
        }
        if (modelFile.length() < MIN_EXPECTED_MODEL_BYTES) {
            throw IllegalArgumentException("Model file is too small to be a valid .litertlm artifact: $modelDetails")
        }
    }

    private fun tryInitializeLocked(
        modelPath: String,
        modelDetails: String,
        backend: Backend,
        backendName: String,
        maxTokens: Int
    ): String? {
        var newEngine: Engine? = null
        val operation = "LiteRT init backend=$backendName"
        DiagnosticsLogger.beginOperation(operation)
        return try {
            DiagnosticsLogger.event(TAG, "Creating EngineConfig backend=$backendName model=$modelDetails")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = maxTokens,
                cacheDir = context.cacheDir.absolutePath
            )
            newEngine = Engine(config)
            DiagnosticsLogger.event(TAG, "Calling Engine.initialize backend=$backendName")
            newEngine.initialize()
            DiagnosticsLogger.event(TAG, "Engine initialized; conversations will be created per generation")

            engine = newEngine
            currentBackend = backendName
            isReady = true
            DiagnosticsLogger.event(TAG, "Engine initialized with $currentBackend backend for $modelDetails")
            null
        } catch (t: Throwable) {
            safeClose(newEngine, "engine after failed $backendName init")
            val diagnostic = buildInitializationDiagnostic(backendName, modelDetails, t)
            DiagnosticsLogger.error(TAG, diagnostic, t)
            diagnostic
        } finally {
            DiagnosticsLogger.endOperation(operation)
        }
    }

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
                            "powered by Google's Gemma LLM via LiteRT."
                    )
                ),
                samplerConfig = samplerConfig
            )
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun generateSingleTurnLocked(prompt: String): Flow<String> = flow {
        val eng = ensureReady()
        lastGenerationUsage = null
        val conv = createNewConversation(eng, currentSamplerConfig)
        try {
            conv.sendMessageAsync(prompt).collect { token ->
                emit(token.toString())
            }
            collectGenerationUsage(conv)
        } finally {
            safeClose(conv, "generation conversation")
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun collectGenerationUsage(conv: com.google.ai.edge.litertlm.Conversation) {
        try {
            val benchmark = conv.getBenchmarkInfo()
            lastGenerationUsage = GenerationUsage(
                promptTokens = benchmark.lastPrefillTokenCount,
                completionTokens = benchmark.lastDecodeTokenCount
            )
            DiagnosticsLogger.event(
                TAG,
                "generation usage promptTokens=${benchmark.lastPrefillTokenCount} completionTokens=${benchmark.lastDecodeTokenCount}"
            )
        } catch (t: Throwable) {
            lastGenerationUsage = null
            DiagnosticsLogger.warn(TAG, "generation usage unavailable: ${t.message ?: t.javaClass.name}")
        }
    }

    suspend fun generateText(prompt: String): Flow<String> = flow {
        sdkMutex.withLock {
            val operation = "LiteRT generation backend=$currentBackend promptChars=${prompt.length}"
            DiagnosticsLogger.beginOperation(operation)
            DiagnosticsLogger.event(TAG, "generateText start promptChars=${prompt.length}")
            try {
                generateSingleTurnLocked(prompt).collect { token ->
                    emit(token)
                }
                DiagnosticsLogger.event(TAG, "generateText complete")
            } catch (t: Throwable) {
                DiagnosticsLogger.error(TAG, "generateText failed", t)
                throw t
            } finally {
                DiagnosticsLogger.endOperation(operation)
            }
        }
    }

    suspend fun generateStatelessText(prompt: String): Flow<String> = flow {
        sdkMutex.withLock {
            val operation = "LiteRT stateless generation backend=$currentBackend promptChars=${prompt.length}"
            DiagnosticsLogger.beginOperation(operation)
            DiagnosticsLogger.event(TAG, "generateStatelessText start promptChars=${prompt.length}")
            try {
                generateSingleTurnLocked(prompt).collect { token ->
                    emit(token)
                }
                DiagnosticsLogger.event(TAG, "generateStatelessText complete")
            } catch (t: Throwable) {
                DiagnosticsLogger.error(TAG, "generateStatelessText failed", t)
                throw t
            } finally {
                DiagnosticsLogger.endOperation(operation)
            }
        }
    }

    fun getLastGenerationUsage(): GenerationUsage? = lastGenerationUsage

    suspend fun analyzeImage(imagePath: String, prompt: String): Flow<String> {
        throw UnsupportedOperationException(
            "Vision is disabled for the current LiteRT-LM engine path; text-only models must not call Content.ImageFile."
        )
    }

    fun clearHistory() {
        DiagnosticsLogger.event(TAG, "clearHistory requested; no native conversation is retained")
    }

    fun getBackend(): String = currentBackend

    fun shutdown() {
        DiagnosticsLogger.event(TAG, "shutdown requested")
        runBlocking(Dispatchers.IO) {
            sdkMutex.withLock {
                isReady = false
                closeLocked()
            }
        }
    }

    private fun ensureReady(): Engine {
        if (!isReady) throw IllegalStateException("Engine not ready")
        return engine ?: throw IllegalStateException("Engine not initialized")
    }

    private fun closeLocked() {
        safeClose(engine, "engine")
        engine = null
    }

    private fun safeClose(closeable: Any?, label: String) {
        if (closeable == null) return
        val operation = "LiteRT close $label"
        DiagnosticsLogger.beginOperation(operation)
        try {
            when (closeable) {
                is com.google.ai.edge.litertlm.Conversation -> closeable.close()
                is Engine -> closeable.close()
            }
        } catch (t: Throwable) {
            DiagnosticsLogger.warn(TAG, "Ignoring LiteRT-LM close failure for $label", t)
        } finally {
            DiagnosticsLogger.endOperation(operation)
        }
    }
}
