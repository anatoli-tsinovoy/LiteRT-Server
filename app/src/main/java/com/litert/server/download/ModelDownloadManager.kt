package com.litert.server.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import java.util.concurrent.TimeUnit

private const val MIN_CUSTOM_MODEL_BYTES = 100_000_000L
private const val FREE_SPACE_HEADROOM_BYTES = 512L * 1024L * 1024L

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
    val repoId: String? = null
) {
    val estimatedGb: Float
        get() = estimatedBytes / 1024f / 1024f / 1024f
}

@Serializable
enum class ModelSource { BUILT_IN, HUGGING_FACE }

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
            description = "2B MoE · multimodal · LiteRT-LM · 128K ctx",
            estimatedBytes = 2_583_000_000L,
            filename = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            minValidBytes = 2_400_000_000L,
            repoId = "litert-community/gemma-4-E2B-it-litert-lm"
        ),
        ModelDescriptor(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B",
            description = "4B MoE · multimodal · LiteRT-LM · 32K ctx",
            estimatedBytes = 3_654_000_000L,
            filename = "gemma-4-E4B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            minValidBytes = 3_500_000_000L,
            repoId = "litert-community/gemma-4-E4B-it-litert-lm"
        )
    )

    val defaultModel: ModelDescriptor = builtIns.first()
}

@Serializable
private data class RegistryState(
    val selectedModelId: String = ModelCatalog.defaultModel.id,
    val customModels: List<ModelDescriptor> = emptyList()
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
        return ModelCatalog.builtIns + customModels
    }

    fun getActiveModel(): ModelDescriptor =
        getAvailableModels().firstOrNull { it.id == registry.selectedModelId }
            ?: ModelCatalog.defaultModel.also { setModel(it) }

    fun setModel(model: ModelDescriptor) {
        if (registry.selectedModelId == model.id) return
        registry = registry.copy(selectedModelId = model.id)
        saveRegistry()
    }

    fun getModelPath(): String = modelFile(getActiveModel()).absolutePath

    fun getModelArtifacts(): List<ModelArtifact> = getAvailableModels().map { artifactFor(it) }

    fun getInstalledModels(): List<ModelArtifact> = getModelArtifacts().filter { it.isInstalled }

    fun isModelDownloaded(): Boolean = artifactFor(getActiveModel()).isInstalled

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

    fun importActiveModel(inputStream: InputStream): Long {
        val model = getActiveModel()
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

        var existingBytes = if (partialFile.exists()) partialFile.length() else 0L
        val requestBuilder = Request.Builder()
            .url(model.downloadUrl)
            .header("User-Agent", "LiteRT-Server-Android/1.0")
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw IllegalStateException("Download failed: HTTP ${response.code} — ${response.message}")
        }

        if (existingBytes > 0L && response.code != 206) {
            existingBytes = 0L
            partialFile.delete()
        }

        val totalBytes = totalBytes(response.code, response.header("Content-Range"), response.body?.contentLength(), existingBytes, model)
        val remainingBytes = (totalBytes - existingBytes).coerceAtLeast(0L)
        val usableSpace = finalFile.parentFile?.usableSpace ?: 0L
        if (usableSpace in 1 until (remainingBytes + FREE_SPACE_HEADROOM_BYTES)) {
            throw IllegalStateException(
                "Not enough free space. Need about ${formatGb(remainingBytes + FREE_SPACE_HEADROOM_BYTES)} GB available."
            )
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
                        val bytesSinceLastCheck = downloadedBytes - lastSpeedBytes
                        val speedMbps = (bytesSinceLastCheck / 1024f / 1024f) / (elapsed / 1000f)
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
                        lastSpeedTime = now
                        lastSpeedBytes = downloadedBytes
                    }
                }
            }
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

    fun deleteModel(modelId: String = getActiveModel().id) {
        val model = getAvailableModels().firstOrNull { it.id == modelId } ?: return
        modelFile(model).delete()
        partialFile(model).delete()
    }

    fun deleteAllModels() {
        getAvailableModels().forEach { model ->
            modelFile(model).delete()
            partialFile(model).delete()
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
            partialBytes = if (partialFile.exists()) partialFile.length() else 0L,
            isInstalled = finalFile.exists() && sizeBytes >= model.minValidBytes
        )
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
            repoId = repoId
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
        val request = Request.Builder()
            .url("https://huggingface.co/api/models/$repoId")
            .header("User-Agent", "LiteRT-Server-Android/1.0")
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

    private fun modelDirectory(model: ModelDescriptor): File = File(context.getExternalFilesDir("models"), sanitizeFilename(model.id))

    private fun loadRegistry(): RegistryState {
        val serialized = prefs.getString("registry", null) ?: return RegistryState()
        return runCatching { json.decodeFromString<RegistryState>(serialized) }.getOrDefault(RegistryState())
    }

    private fun saveRegistry() {
        prefs.edit().putString("registry", json.encodeToString(registry)).apply()
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
