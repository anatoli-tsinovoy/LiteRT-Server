package com.litert.server.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.litert.server.DiagnosticsLogger

private const val DEFAULT_NATIVE_MAX_TOKENS = 4_096
private const val MIN_NATIVE_MAX_TOKENS = 512
private const val MAX_NATIVE_MAX_TOKENS = 128_000
private const val MIN_CUSTOM_MODEL_BYTES = 100_000_000L
private const val FREE_SPACE_HEADROOM_BYTES = 512L * 1024L * 1024L
private const val PREF_REGISTRY = "registry"
private const val PREF_HF_TOKEN = "hugging_face_token"
private const val HF_TOKEN_KEY_ALIAS = "litert_server_hugging_face_token"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val PARALLEL_DOWNLOAD_SEGMENTS = 4
private const val PARALLEL_DOWNLOAD_MIN_BYTES = 64L * 1024L * 1024L

data class DownloadProgress(
    val progressPercent: Float,
    val downloadedMb: Float,
    val totalMb: Float,
    val speedMbps: Float,
    val etaSeconds: Int,
    val isDone: Boolean = false,
    val error: String? = null
)

@Serializable
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val filename: String,
    val estimatedBytes: Long,
    val minValidBytes: Long,
    val source: ModelSource = ModelSource.BUILT_IN,
    val repoId: String? = null,
    val contextWindowTokens: Int = DEFAULT_NATIVE_MAX_TOKENS,
    val nativeMaxTokens: Int = DEFAULT_NATIVE_MAX_TOKENS
) {
    val estimatedGb: Float
        get() = estimatedBytes / 1024f / 1024f / 1024f
}

@Serializable
enum class ModelSource { BUILT_IN, HUGGING_FACE, LOCAL_FILE }

data class ModelArtifact(
    val model: ModelDescriptor,
    val path: String,
    val sizeBytes: Long,
    val partialBytes: Long,
    val isInstalled: Boolean
) {
    val sizeMb: Float
        get() = sizeBytes / 1024f / 1024f
}

object ModelCatalog {
    val builtIns = listOf(
        ModelDescriptor(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B",
            description = "2B MoE · LiteRT-LM · 128K ctx · native limit configurable",
            estimatedBytes = 2_583_000_000L,
            filename = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            minValidBytes = 2_400_000_000L,
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            contextWindowTokens = 128_000
        ),
        ModelDescriptor(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B",
            description = "4B MoE · LiteRT-LM · 32K ctx · native limit configurable",
            estimatedBytes = 3_654_000_000L,
            filename = "gemma-4-E4B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            minValidBytes = 3_500_000_000L,
            repoId = "litert-community/gemma-4-E4B-it-litert-lm",
            contextWindowTokens = 32_000
        )
    )

    val defaultModel: ModelDescriptor = builtIns.first()
}

@Serializable
private data class RegistryState(
    val selectedModelId: String = ModelCatalog.defaultModel.id,
    val customModels: List<ModelDescriptor> = emptyList(),
    val runtimeSettings: Map<String, ModelRuntimeSettings> = emptyMap()
)

@Serializable
private data class ModelRuntimeSettings(
    val nativeMaxTokens: Int = DEFAULT_NATIVE_MAX_TOKENS
)

@Serializable
private data class HuggingFaceModelInfo(
    val siblings: List<HuggingFaceSibling> = emptyList()
)

@Serializable
private data class HuggingFaceSibling(
    @SerialName("rfilename") val filename: String,
    val size: Long? = null
)

class ModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("model_registry", Context.MODE_PRIVATE)
    private var registry = loadRegistry()

    fun getAvailableModels(): List<ModelDescriptor> {
        val builtInIds = ModelCatalog.builtIns.mapTo(mutableSetOf()) { it.id }
        val customModels = registry.customModels.filterNot { it.id in builtInIds }
        return (ModelCatalog.builtIns + customModels).map { it.withRuntimeSettings() }
    }

    fun getActiveModel(): ModelDescriptor =
        getAvailableModels().firstOrNull { it.id == registry.selectedModelId }
            ?: ModelCatalog.defaultModel.also { setModel(it) }

    fun setModel(model: ModelDescriptor) {
        if (registry.selectedModelId == model.id) return
        registry = registry.copy(selectedModelId = model.id)
        saveRegistry()
    }

    fun setNativeMaxTokens(modelId: String, nativeMaxTokens: Int): ModelDescriptor {
        val normalized = nativeMaxTokens.coerceIn(MIN_NATIVE_MAX_TOKENS, MAX_NATIVE_MAX_TOKENS)
        val model = getAvailableModels().firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown model: $modelId")
        val bounded = normalized.coerceAtMost(model.contextWindowTokens.coerceAtLeast(MIN_NATIVE_MAX_TOKENS))
        registry = registry.copy(
            runtimeSettings = registry.runtimeSettings + (modelId to ModelRuntimeSettings(nativeMaxTokens = bounded))
        )
        saveRegistry()
        return model.copy(nativeMaxTokens = bounded)
    }

    fun getModelPath(): String = modelFile(getActiveModel()).absolutePath

    fun getModelArtifacts(): List<ModelArtifact> = getAvailableModels().map { artifactFor(it) }

    fun getInstalledModels(): List<ModelArtifact> = getModelArtifacts().filter { it.isInstalled }

    fun isModelDownloaded(): Boolean = artifactFor(getActiveModel()).isInstalled

    fun hasHuggingFaceToken(): Boolean = !getHuggingFaceToken().isNullOrBlank()

    fun setHuggingFaceToken(token: String) {
        val normalized = token.trim()
        if (normalized.isEmpty()) {
            clearHuggingFaceToken()
            return
        }
        require(normalized.startsWith("hf_")) { "Hugging Face token must start with hf_" }
        prefs.edit().putString(PREF_HF_TOKEN, encryptToken(normalized)).apply()
    }

    fun clearHuggingFaceToken() {
        prefs.edit().remove(PREF_HF_TOKEN).apply()
    }

    fun addCustomHuggingFaceModel(input: String): ModelDescriptor {
        val candidate = resolveHuggingFaceModel(input)
        val existing = getAvailableModels().firstOrNull { it.id == candidate.id || it.downloadUrl == candidate.downloadUrl }
        if (existing != null) {
            setModel(existing)
            return existing
        }

        registry = registry.copy(
            selectedModelId = candidate.id,
            customModels = registry.customModels + candidate
        )
        saveRegistry()
        return candidate
    }

    fun importActiveModel(inputStream: InputStream): Long =
        importModelFile(getActiveModel(), inputStream)

    fun importLocalModel(displayFilename: String, inputStream: InputStream): ModelDescriptor {
        val filename = sanitizeFilename(displayFilename)
        require(filename.endsWith(".litertlm", ignoreCase = true)) { "Import a .litertlm file" }
        val baseName = filename.removeSuffix(".litertlm").ifBlank { "Local LiteRT Model" }
        val id = "local-${sanitizeFilename("${baseName.lowercase()}-${System.currentTimeMillis()}")}"
        val model = ModelDescriptor(
            id = id,
            displayName = baseName.split('-', '_', '.', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
                .ifBlank { "Local LiteRT Model" },
            description = "Local LiteRT-LM model · native limit configurable",
            downloadUrl = "",
            filename = filename,
            estimatedBytes = MIN_CUSTOM_MODEL_BYTES,
            minValidBytes = MIN_CUSTOM_MODEL_BYTES,
            source = ModelSource.LOCAL_FILE,
            contextWindowTokens = MAX_NATIVE_MAX_TOKENS
        )
        val previousSelection = registry.selectedModelId
        registry = registry.copy(
            selectedModelId = model.id,
            customModels = registry.customModels + model
        )
        saveRegistry()

        try {
            importModelFile(model, inputStream)
            return model.withRuntimeSettings()
        } catch (t: Throwable) {
            modelFile(model).delete()
            partialFile(model).delete()
            registry = registry.copy(
                selectedModelId = previousSelection,
                customModels = registry.customModels.filterNot { it.id == model.id },
                runtimeSettings = registry.runtimeSettings - model.id
            )
            saveRegistry()
            throw t
        }
    }

    private fun importModelFile(model: ModelDescriptor, inputStream: InputStream): Long {
        val finalFile = modelFile(model)
        val partialFile = partialFile(model)
        finalFile.parentFile?.mkdirs()
        partialFile.delete()

        inputStream.use { input ->
            FileOutputStream(partialFile, false).use { output ->
                input.copyTo(output)
            }
        }

        if (partialFile.length() < model.minValidBytes) {
            partialFile.delete()
            throw IllegalStateException("Selected file is too small for ${model.displayName}")
        }

        finalFile.delete()
        if (!partialFile.renameTo(finalFile)) {
            partialFile.copyTo(finalFile, overwrite = true)
            partialFile.delete()
        }
        return finalFile.length()
    }

    fun downloadModel(): Flow<DownloadProgress> = flow {
        val model = getActiveModel()
        val finalFile = modelFile(model)
        val partialFile = partialFile(model)
        finalFile.parentFile?.mkdirs()

        if (artifactFor(model).isInstalled) {
            emit(
                DownloadProgress(
                    progressPercent = 1f,
                    downloadedMb = finalFile.length() / 1024f / 1024f,
                    totalMb = finalFile.length() / 1024f / 1024f,
                    speedMbps = 0f,
                    etaSeconds = 0,
                    isDone = true
                )
            )
            return@flow
        }

        var totalBytes = 0L
        DiagnosticsLogger.beginOperation("Model download ${model.id}")
        try {
            val metadata = fetchDownloadMetadata(model)
            totalBytes = metadata.totalBytes
            val usableSpace = finalFile.parentFile?.usableSpace ?: 0L
            val alreadyDownloadedBytes = if (partialFile.exists()) {
                partialFile.length()
            } else {
                segmentFiles(model).sumOf { if (it.exists()) it.length() else 0L }
            }
            val remainingBytes = (totalBytes - alreadyDownloadedBytes).coerceAtLeast(0L)
            DiagnosticsLogger.event(
                "ModelDownload",
                "start model=${model.id} totalBytes=$totalBytes alreadyBytes=$alreadyDownloadedBytes supportsRanges=${metadata.supportsRanges} usableSpace=$usableSpace"
            )
            if (usableSpace in 1 until (remainingBytes + FREE_SPACE_HEADROOM_BYTES)) {
                throw IllegalStateException(
                    "Not enough free space. Need about ${formatGb(remainingBytes + FREE_SPACE_HEADROOM_BYTES)} GB available."
                )
            }

            if (metadata.supportsRanges && !partialFile.exists() && totalBytes >= PARALLEL_DOWNLOAD_MIN_BYTES) {
                try {
                    downloadParallelSegments(model, partialFile, totalBytes)
                } catch (t: Throwable) {
                    DiagnosticsLogger.warn(
                        "ModelDownload",
                        "parallel download failed for ${model.id}; retrying with single stream",
                        t
                    )
                    cleanupSegmentFiles(model)
                    partialFile.delete()
                    downloadSingleStream(model, partialFile, totalBytes)
                }
            } else {
                cleanupSegmentFiles(model)
                try {
                    downloadSingleStream(model, partialFile, totalBytes)
                } catch (t: Throwable) {
                    if (partialFile.exists() && partialFile.length() > 0L) {
                        DiagnosticsLogger.warn(
                            "ModelDownload",
                            "resumed download failed for ${model.id}; retrying from byte 0",
                            t
                        )
                        partialFile.delete()
                        downloadSingleStream(model, partialFile, totalBytes)
                    } else {
                        throw t
                    }
                }
            }
            DiagnosticsLogger.event("ModelDownload", "download body complete model=${model.id} bytes=${partialFile.length()}")
        } catch (t: Throwable) {
            DiagnosticsLogger.error("ModelDownload", "download failed model=${model.id}", t)
            throw t
        } finally {
            DiagnosticsLogger.endOperation("Model download ${model.id}")
        }

        if (partialFile.length() < model.minValidBytes) {
            partialFile.delete()
            throw IllegalStateException("Downloaded file too small — may be corrupted. Please retry.")
        }

        finalFile.delete()
        if (!partialFile.renameTo(finalFile)) {
            partialFile.copyTo(finalFile, overwrite = true)
            partialFile.delete()
        }

        emit(
            DownloadProgress(
                progressPercent = 1f,
                downloadedMb = finalFile.length() / 1024f / 1024f,
                totalMb = totalBytes / 1024f / 1024f,
                speedMbps = 0f,
                etaSeconds = 0,
                isDone = true
            )
        )
    }.flowOn(Dispatchers.IO)

    private data class DownloadMetadata(
        val totalBytes: Long,
        val supportsRanges: Boolean
    )

    private fun fetchDownloadMetadata(model: ModelDescriptor): DownloadMetadata {
        val request = authenticatedRequestBuilder(model.downloadUrl)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                DiagnosticsLogger.warn(
                    "ModelDownload",
                    "metadata request failed model=${model.id} code=${response.code} message=${response.message}"
                )
                throw IllegalStateException("Download failed: HTTP ${response.code} — ${response.message}")
            }
            val totalBytes = totalBytes(
                response.code,
                response.header("Content-Range"),
                response.body?.contentLength(),
                0L,
                model
            )
            DiagnosticsLogger.event(
                "ModelDownload",
                "metadata model=${model.id} code=${response.code} totalBytes=$totalBytes contentRange=${response.header("Content-Range") != null}"
            )
            return DownloadMetadata(
                totalBytes = totalBytes,
                supportsRanges = response.code == 206 && response.header("Content-Range")?.contains('/') == true
            )
        }
    }

    private suspend fun FlowCollector<DownloadProgress>.downloadSingleStream(
        model: ModelDescriptor,
        partialFile: File,
        totalBytes: Long
    ) {
        var existingBytes = if (partialFile.exists()) partialFile.length() else 0L
        val requestBuilder = authenticatedRequestBuilder(model.downloadUrl)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                DiagnosticsLogger.warn(
                    "ModelDownload",
                    "single stream failed model=${model.id} existingBytes=$existingBytes code=${response.code} message=${response.message}"
                )
                throw IllegalStateException("Download failed: HTTP ${response.code} — ${response.message}")
            }

            if (existingBytes > 0L && response.code != 206) {
                existingBytes = 0L
                partialFile.delete()
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloadedBytes = existingBytes
            var lastSpeedTime = System.currentTimeMillis()
            var lastSpeedBytes = downloadedBytes

            FileOutputStream(partialFile, existingBytes > 0L).use { outputStream ->
                body.byteStream().use { inputStream ->
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastSpeedTime
                        if (elapsed >= 1000) {
                            val speedMbps = speedMbps(downloadedBytes - lastSpeedBytes, elapsed)
                            emitProgress(downloadedBytes, totalBytes, speedMbps)
                            lastSpeedTime = now
                            lastSpeedBytes = downloadedBytes
                        }
                    }
                }
            }
        }
    }

    private suspend fun FlowCollector<DownloadProgress>.downloadParallelSegments(
        model: ModelDescriptor,
        partialFile: File,
        totalBytes: Long
    ) {
        val segments = downloadSegments(model, totalBytes)
        val downloadedBytes = AtomicLong(segments.sumOf { it.file.length().coerceAtMost(it.length) })
        val error = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(segments.size)
        val executor = Executors.newFixedThreadPool(segments.size.coerceAtLeast(1))

        segments.forEach { segment ->
            executor.execute {
                try {
                    downloadSegment(model, segment, downloadedBytes)
                } catch (throwable: Throwable) {
                    error.compareAndSet(null, throwable)
                } finally {
                    latch.countDown()
                }
            }
        }

        var lastSpeedTime = System.currentTimeMillis()
        var lastSpeedBytes = downloadedBytes.get()
        try {
            while (latch.count > 0L) {
                Thread.sleep(1000)
                error.get()?.let { throw it }
                val now = System.currentTimeMillis()
                val currentBytes = downloadedBytes.get()
                val elapsed = now - lastSpeedTime
                val speedMbps = speedMbps(currentBytes - lastSpeedBytes, elapsed)
                emitProgress(currentBytes, totalBytes, speedMbps)
                lastSpeedTime = now
                lastSpeedBytes = currentBytes
            }
            error.get()?.let { throw it }
        } finally {
            executor.shutdownNow()
        }

        partialFile.delete()
        FileOutputStream(partialFile, false).use { output ->
            segments.sortedBy { it.start }.forEach { segment ->
                require(segment.file.length() == segment.length) {
                    "Incomplete download segment for ${model.displayName}"
                }
                segment.file.inputStream().use { input -> input.copyTo(output) }
                segment.file.delete()
            }
        }
    }

    private data class DownloadSegment(
        val index: Int,
        val start: Long,
        val endInclusive: Long,
        val file: File
    ) {
        val length: Long = endInclusive - start + 1L
    }

    private fun downloadSegments(model: ModelDescriptor, totalBytes: Long): List<DownloadSegment> {
        val segmentCount = PARALLEL_DOWNLOAD_SEGMENTS.coerceAtMost((totalBytes / PARALLEL_DOWNLOAD_MIN_BYTES).coerceAtLeast(1L).toInt())
        val segmentSize = (totalBytes + segmentCount - 1L) / segmentCount
        return (0 until segmentCount).mapNotNull { index ->
            val start = index * segmentSize
            if (start >= totalBytes) return@mapNotNull null
            val end = minOf(totalBytes - 1L, start + segmentSize - 1L)
            DownloadSegment(index, start, end, segmentFile(model, index))
        }
    }

    private fun downloadSegment(
        model: ModelDescriptor,
        segment: DownloadSegment,
        downloadedBytes: AtomicLong
    ) {
        if (segment.file.exists() && segment.file.length() > segment.length) {
            downloadedBytes.addAndGet(-segment.length)
            segment.file.delete()
        }
        val existingBytes = if (segment.file.exists()) segment.file.length() else 0L
        if (existingBytes == segment.length) return

        val request = authenticatedRequestBuilder(model.downloadUrl)
            .header("Range", "bytes=${segment.start + existingBytes}-${segment.endInclusive}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 206) {
                DiagnosticsLogger.warn(
                    "ModelDownload",
                    "segment failed model=${model.id} segment=${segment.index + 1} start=${segment.start + existingBytes} end=${segment.endInclusive} code=${response.code}"
                )
                throw IllegalStateException("Download segment ${segment.index + 1} failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty response body")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            FileOutputStream(segment.file, existingBytes > 0L).use { output ->
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes.addAndGet(read.toLong())
                    }
                }
            }
        }
    }

    private suspend fun FlowCollector<DownloadProgress>.emitProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        speedMbps: Float
    ) {
        val remaining = totalBytes - downloadedBytes
        val etaSec = if (speedMbps > 0f) (remaining / 1024 / 1024 / speedMbps).toInt() else 0
        emit(
            DownloadProgress(
                progressPercent = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
                downloadedMb = downloadedBytes / 1024f / 1024f,
                totalMb = totalBytes / 1024f / 1024f,
                speedMbps = speedMbps,
                etaSeconds = etaSec.coerceAtLeast(0)
            )
        )
    }

    private fun speedMbps(bytes: Long, elapsedMillis: Long): Float =
        if (elapsedMillis > 0L) (bytes / 1024f / 1024f) / (elapsedMillis / 1000f) else 0f

    fun deleteModel(modelId: String = getActiveModel().id) {
        val model = getAvailableModels().firstOrNull { it.id == modelId } ?: return
        modelFile(model).delete()
        partialFile(model).delete()
        cleanupSegmentFiles(model)
    }

    fun deleteAllModels() {
        getAvailableModels().forEach { model ->
            modelFile(model).delete()
            partialFile(model).delete()
            cleanupSegmentFiles(model)
        }
        context.cacheDir.deleteRecursively()
        context.cacheDir.mkdirs()
    }

    private fun artifactFor(model: ModelDescriptor): ModelArtifact {
        val finalFile = modelFile(model)
        val partialFile = partialFile(model)
        val sizeBytes = if (finalFile.exists()) finalFile.length() else 0L
        return ModelArtifact(
            model = model,
            path = finalFile.absolutePath,
            sizeBytes = sizeBytes,
            partialBytes = partialBytes(model, partialFile),
            isInstalled = finalFile.exists() && sizeBytes >= model.minValidBytes
        )
    }

    private fun partialBytes(model: ModelDescriptor, partialFile: File): Long {
        if (partialFile.exists()) return partialFile.length()
        return segmentFiles(model).sumOf { if (it.exists()) it.length() else 0L }
    }

    private fun resolveHuggingFaceModel(input: String): ModelDescriptor {
        val normalized = input.trim()
        require(normalized.isNotEmpty()) { "Enter a Hugging Face model URL" }

        val uri = URI(normalized)
        require(uri.scheme == "https" && uri.host == "huggingface.co") {
            "Use a https://huggingface.co/... URL"
        }

        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        require(segments.size >= 2) { "Hugging Face URL must include owner and repo" }

        val repoId = "${segments[0]}/${segments[1]}"
        val fileFromUrl = fileFromDirectModelUrl(segments)
        val filename = fileFromUrl ?: fetchDefaultLiteRtFile(repoId)
        val downloadUrl = if (fileFromUrl != null) {
            normalized.replace("/blob/", "/resolve/")
        } else {
            "https://huggingface.co/$repoId/resolve/main/$filename"
        }
        val localFilename = sanitizeFilename(filename.substringAfterLast('/'))
        val displayName = displayNameFor(repoId, localFilename)

        return ModelDescriptor(
            id = stableModelId(repoId, localFilename),
            displayName = displayName,
            description = "Custom Hugging Face LiteRT-LM model · $repoId",
            downloadUrl = downloadUrl,
            filename = localFilename,
            estimatedBytes = MIN_CUSTOM_MODEL_BYTES,
            minValidBytes = MIN_CUSTOM_MODEL_BYTES,
            source = ModelSource.HUGGING_FACE,
            repoId = repoId,
            contextWindowTokens = MAX_NATIVE_MAX_TOKENS
        )
    }

    private fun fileFromDirectModelUrl(segments: List<String>): String? {
        val markerIndex = segments.indexOfFirst { it == "resolve" || it == "blob" }
        if (markerIndex < 0 || markerIndex + 2 >= segments.size) return null
        val filename = segments.drop(markerIndex + 2).joinToString("/")
        require(filename.endsWith(".litertlm", ignoreCase = true)) {
            "Direct model URL must point to a .litertlm file"
        }
        return filename
    }

    private fun fetchDefaultLiteRtFile(repoId: String): String {
        val request = authenticatedRequestBuilder("https://huggingface.co/api/models/$repoId")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Could not read Hugging Face model repo: HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw IllegalStateException("Empty Hugging Face response")
        val info = json.decodeFromString<HuggingFaceModelInfo>(body)
        val liteRtFiles = info.siblings.map { it.filename }.filter { it.endsWith(".litertlm", ignoreCase = true) }
        return liteRtFiles.firstOrNull { !it.contains("web", ignoreCase = true) }
            ?: liteRtFiles.firstOrNull()
            ?: throw IllegalStateException("No .litertlm file found in $repoId")
    }

    private fun totalBytes(
        responseCode: Int,
        contentRange: String?,
        contentLength: Long?,
        existingBytes: Long,
        model: ModelDescriptor
    ): Long = when {
        responseCode == 206 -> contentRange?.substringAfterLast('/')?.toLongOrNull()
            ?: ((contentLength ?: 0L) + existingBytes).takeIf { it > existingBytes }
            ?: model.estimatedBytes
        contentLength != null && contentLength > 0L -> contentLength
        else -> model.estimatedBytes
    }.coerceAtLeast(model.minValidBytes)

    private fun modelFile(model: ModelDescriptor): File = File(modelDirectory(model), model.filename)

    private fun partialFile(model: ModelDescriptor): File = File(modelDirectory(model), "${model.filename}.part")
    private fun segmentFile(model: ModelDescriptor, index: Int): File =
        File(modelDirectory(model), "${model.filename}.part.$index")

    private fun segmentFiles(model: ModelDescriptor): List<File> =
        (0 until PARALLEL_DOWNLOAD_SEGMENTS).map { segmentFile(model, it) }

    private fun cleanupSegmentFiles(model: ModelDescriptor) {
        segmentFiles(model).forEach { it.delete() }
    }


    private fun modelDirectory(model: ModelDescriptor): File = File(context.getExternalFilesDir("models"), sanitizeFilename(model.id))

    private fun ModelDescriptor.withRuntimeSettings(): ModelDescriptor {
        val settings = registry.runtimeSettings[id] ?: return this
        return copy(
            nativeMaxTokens = settings.nativeMaxTokens
                .coerceIn(MIN_NATIVE_MAX_TOKENS, contextWindowTokens.coerceAtLeast(MIN_NATIVE_MAX_TOKENS))
        )
    }

    private fun loadRegistry(): RegistryState {
        val serialized = prefs.getString(PREF_REGISTRY, null) ?: return RegistryState()
        return runCatching { json.decodeFromString<RegistryState>(serialized) }.getOrDefault(RegistryState())
    }

    private fun saveRegistry() {
        prefs.edit().putString(PREF_REGISTRY, json.encodeToString(registry)).apply()
    }

    private fun authenticatedRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "LiteRT-Server-Android/1.0")
        val token = getHuggingFaceToken()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun getHuggingFaceToken(): String? {
        val encrypted = prefs.getString(PREF_HF_TOKEN, null) ?: return null
        return runCatching { decryptToken(encrypted) }
            .getOrElse {
                clearHuggingFaceToken()
                null
            }
    }

    private fun encryptToken(token: String): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherText = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipherText, Base64.NO_WRAP)}"
    }

    private fun decryptToken(encrypted: String): String {
        val parts = encrypted.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted token" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(HF_TOKEN_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            HF_TOKEN_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
    private fun stableModelId(repoId: String, filename: String): String =
        "hf-${sanitizeFilename("$repoId-$filename".lowercase())}"

    private fun sanitizeFilename(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifBlank { "model.litertlm" }

    private fun displayNameFor(repoId: String, filename: String): String {
        val repoName = repoId.substringAfter('/').removeSuffix("-litert-lm")
        return repoName.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                if (token.any { it.isDigit() }) token.uppercase() else token.replaceFirstChar { it.uppercase() }
            }
            .ifBlank { filename.removeSuffix(".litertlm") }
    }

    private fun formatGb(bytes: Long): String = "%.1f".format(bytes / 1024f / 1024f / 1024f)
}
