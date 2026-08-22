package com.litert.server.engine

import android.content.Context
import android.util.Log
import com.litert.server.BuildConfig
import com.google.ai.edge.litertlm.LogSeverity
import java.io.File
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GenerationUsage(
    val promptTokens: Int,
    val completionTokens: Int,
) {
    val totalTokens: Int
        get() = promptTokens + completionTokens
}

class LiteRTEngine(private val context: Context) {
    companion object {
        private const val TAG = "LiteRTEngine"
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.9
        private const val CANCELLATION_DRAIN_INTERVAL_MILLIS = 100L
        private const val CANCELLATION_DRAIN_ATTEMPTS = 20
        private val GPU_LOG_MARKERS =
            listOf(
                "litert",
                "tflite",
                "native",
                "opencl",
                "clgl",
                "gpu",
                "accelerator",
                "adreno",
                "delegate",
            )
    }

    private val mutex = Mutex()
    private val conversationLock = Any()
    private var activeConversation: Conversation? = null

    fun cancelActiveConversation() {
        synchronized(conversationLock) {
            activeConversation?.let(::cancelConversation)
        }
    }
    private var nativeEngine: Engine? = null

    @Volatile
    private var ready = false

    val isReady: Boolean
        get() = ready

    @Volatile
    private var backendName = "CPU"

    val backend: String
        get() = backendName

    @Volatile
    private var gpuFailure: String? = null

    val gpuFallbackReason: String?
        get() = gpuFailure

    suspend fun initialize(
        modelPath: String,
        useGpu: Boolean,
        maxTokens: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                closeEngineLocked()
                ready = false
                gpuFailure = null
                enableBenchmark()
                if (useGpu) {
                    Engine.setNativeMinLogSeverity(LogSeverity.VERBOSE)
                }
                val backends =
                    if (useGpu) {
                        listOf(Backend.GPU() to "GPU", Backend.CPU() to "CPU")
                    } else {
                        listOf(Backend.CPU() to "CPU")
                    }

                var lastFailure: Throwable? = null
                for ((backend, name) in backends) {
                    var candidate: Engine? = null
                    try {
                        candidate =
                            Engine(
                                EngineConfig(
                                    modelPath = modelPath,
                                    backend = backend,
                                    maxNumTokens = maxTokens,
                                    cacheDir = backendCacheDir(name),
                                )
                            )
                        candidate.initialize()
                        nativeEngine = candidate
                        backendName = name
                        ready = true
                        return@withLock Result.success(Unit)
                    } catch (error: Exception) {
                        if (error is CancellationException) {
                            candidate?.let(::closeFailedEngine)
                            throw error
                        }
                        lastFailure = error
                        candidate?.let(::closeFailedEngine)
                        if (name == "GPU") {
                            val diagnostics = captureGpuDiagnostics()
                            gpuFailure = failureSummary(error, diagnosticHint(diagnostics))
                            Engine.setNativeMinLogSeverity(LogSeverity.INFO)
                            Log.e(TAG, "GPU initialization failed; falling back to CPU", error)
                            diagnostics?.let { Log.e(TAG, "GPU native diagnostics:\n$it") }
                        }
                    }
                }

                ready = false
                Result.failure(
                    lastFailure ?: IllegalStateException("LiteRT-LM engine initialization failed")
                )
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                ready = false
                Result.failure(error)
            }
        }
    }

    suspend fun generate(
        prompt: String,
        temperature: Double,
        onChunk: suspend (String) -> Unit,
    ): GenerationUsage = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(ready) { "LiteRT-LM engine is not ready" }
            val currentEngine = nativeEngine
                ?: error("LiteRT-LM engine is not initialized")
            check(currentEngine.isInitialized()) { "LiteRT-LM engine is not initialized" }

            val conversation =
                currentEngine.createConversation(
                    ConversationConfig(
                        samplerConfig =
                            SamplerConfig(
                                topK = DEFAULT_TOP_K,
                                topP = DEFAULT_TOP_P,
                                temperature = temperature,
                            )
                    )
                )
            synchronized(conversationLock) {
                activeConversation = conversation
            }
            var completionChars = 0
            try {
                conversation.sendMessageAsync(prompt).collect { message ->
                    val text = message.toString()
                    if (text.isNotEmpty()) {
                        completionChars += text.length
                        onChunk(text)
                    }
                }
                usageFor(
                    conversation = conversation,
                    promptChars = prompt.length,
                    completionChars = completionChars,
                )
            } catch (cancellation: CancellationException) {
                drainCancellation(conversation)
                throw cancellation
            } finally {
                synchronized(conversationLock) {
                    if (activeConversation === conversation) {
                        activeConversation = null
                    }
                    closeConversation(conversation)
                }
            }
        }
    }

    suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                closeEngineLocked()
                ready = false
            }
        }
    }

    private fun closeEngineLocked() {
        val currentEngine = nativeEngine ?: return
        nativeEngine = null
        ready = false
        if (currentEngine.isInitialized()) {
            currentEngine.close()
        }
    }

    private fun closeFailedEngine(engine: Engine) {
        runCatching {
            if (engine.isInitialized()) {
                engine.close()
            }
        }
    }
    private suspend fun drainCancellation(conversation: Conversation) {
        withContext(NonCancellable + Dispatchers.IO) {
            repeat(CANCELLATION_DRAIN_ATTEMPTS) { attempt ->
                cancelConversation(conversation)
                if (attempt + 1 < CANCELLATION_DRAIN_ATTEMPTS) {
                    delay(CANCELLATION_DRAIN_INTERVAL_MILLIS)
                }
            }
        }
    }


    private fun cancelConversation(conversation: Conversation) {
        runCatching { conversation.cancelProcess() }
    }

    private fun closeConversation(conversation: Conversation) {
        runCatching { conversation.close() }
    }

    @OptIn(ExperimentalApi::class)
    private fun enableBenchmark() {
        ExperimentalFlags.enableBenchmark = true
    }


    @OptIn(ExperimentalApi::class)
    private fun usageFor(
        conversation: Conversation,
        promptChars: Int,
        completionChars: Int,
    ): GenerationUsage {
        return try {
            val benchmark = conversation.getBenchmarkInfo()
            GenerationUsage(
                promptTokens = benchmark.lastPrefillTokenCount,
                completionTokens = benchmark.lastDecodeTokenCount,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            GenerationUsage(
                promptTokens = estimateTokens(promptChars),
                completionTokens = estimateTokens(completionChars),
            )
        }
    }

    private fun backendCacheDir(backend: String): String =
        File(
            context.cacheDir,
            "litert/${BuildConfig.LITERT_LM_VERSION}/${backend.lowercase()}",
        ).apply {
            check(isDirectory || mkdirs()) { "Unable to create LiteRT $backend cache directory" }
        }.absolutePath

    private fun captureGpuDiagnostics(): String? = runCatching {
        val output =
            ProcessBuilder("/system/bin/logcat", "-d", "-v", "brief", "-t", "500")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { it.readText() }
        output.lineSequence()
            .filter { line ->
                GPU_LOG_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
            }
            .toList()
            .takeLast(20)
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun diagnosticHint(diagnostics: String?): String? {
        if (diagnostics == null) return null
        return when {
            diagnostics.contains("graph is not fully delegated", ignoreCase = true) ->
                "The selected model is not fully supported by the LiteRT GPU delegate."
            diagnostics.contains("Failed to load OpenCL", ignoreCase = true) ->
                "LiteRT could not load the device OpenCL driver."
            diagnostics.contains("Failed to create default OpenCL device", ignoreCase = true) ->
                "LiteRT could not create an OpenCL GPU device."
            diagnostics.contains("GPU accelerator could not be loaded", ignoreCase = true) ->
                "LiteRT could not load its packaged GPU accelerator."
            else ->
                diagnostics.lineSequence()
                    .lastOrNull { it.startsWith("E/") }
                    ?.take(500)
        }
    }

    private fun failureSummary(error: Throwable, diagnostics: String?): String {
        val chain = generateSequence(error) { it.cause }
            .mapNotNull { cause ->
                cause.message?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    "${cause.javaClass.simpleName}: $it"
                }
            }
            .distinct()
            .joinToString(" → ")
            .ifBlank { error.javaClass.name }
        return listOfNotNull(chain, diagnostics)
            .joinToString("\n")
            .take(4_000)
    }
    private fun estimateTokens(chars: Int): Int =
        ((chars + 3) / 4).coerceAtLeast(0)
}
