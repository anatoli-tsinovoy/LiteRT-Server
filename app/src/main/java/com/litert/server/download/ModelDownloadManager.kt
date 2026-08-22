package com.litert.server.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val DEFAULT_NATIVE_MAX_TOKENS = 4_096
private const val MIN_NATIVE_MAX_TOKENS = 512
private const val MAX_NATIVE_MAX_TOKENS = 128_000
private const val PREFS_NAME = "model_registry"
private const val PREF_REGISTRY = "registry"
private const val PREF_HF_TOKEN_CIPHERTEXT = "hf_token_ciphertext"
private const val PREF_HF_TOKEN_IV = "hf_token_iv"
private const val HF_TOKEN_KEY_ALIAS = "litert_server_hf_token"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val HUGGING_FACE_HOST = "huggingface.co"
private const val MODEL_DIRECTORY = "models"
private const val BUFFER_SIZE = 64 * 1024
private const val PROGRESS_INTERVAL_MILLIS = 1_000L


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
enum class ToolPromptProfile {
    TAGGED_JSON,
    STRICT_JSON_RELAY
}

@Serializable
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val filename: String,
    val expectedBytes: Long,
    val contextWindowTokens: Int,
    val nativeMaxTokens: Int,
    val isCustom: Boolean,
    val toolPromptProfile: ToolPromptProfile = ToolPromptProfile.TAGGED_JSON
)

data class ModelArtifact(
    val model: ModelDescriptor,
    val path: String,
    val installedBytes: Long,
    val partialBytes: Long,
    val isInstalled: Boolean
)

object ModelCatalog {
    val builtIns: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B",
            description = "0.6B · GPU-tested LiteRT-LM · 2K context",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/8414150f2e9dcc82449bcc9c5abc404b399a4d06/qwen3_0_6b_mixed_int4.litertlm",
            filename = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            expectedBytes = 497_664_000L,
            contextWindowTokens = 2_048,
            nativeMaxTokens = 2_048,
            isCustom = false,
            toolPromptProfile = ToolPromptProfile.TAGGED_JSON
        ),
        ModelDescriptor(
            id = "gemma-3n-e4b-it",
            displayName = "Gemma 3n E4B",
            description = "4B effective · INT4 · GPU-tested on Snapdragon 8 Gen 3 · gated",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/297ed75955702dec3503e00c2c2ecbbf475300bc/gemma-3n-e4b-it-int4.litertlm",
            filename = "gemma-3n-E4B-it-int4.litertlm",
            expectedBytes = 4_919_541_760L,
            contextWindowTokens = 32_768,
            nativeMaxTokens = 4_096,
            isCustom = false,
            toolPromptProfile = ToolPromptProfile.STRICT_JSON_RELAY
        )
    )

    val defaultModel: ModelDescriptor = builtIns.first()
}

@Serializable
private data class RegistryState(
    val selectedModelId: String = ModelCatalog.defaultModel.id,
    val customModels: List<ModelDescriptor> = emptyList(),
    val nativeMaxTokens: Map<String, Int> = emptyMap()
)

private data class DirectModelUrl(
    val normalizedUrl: String,
    val owner: String,
    val repository: String,
    val revision: String,
    val filePath: String,
    val filename: String
)

private data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long
)

class ModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var registry = loadRegistry()

    fun getModelArtifacts(): List<ModelArtifact> = availableModels().map(::artifactFor)

    fun getActiveModel(): ModelDescriptor {
        val active = availableModels().firstOrNull { it.id == registry.selectedModelId }
        if (active != null) return active

        val fallback = ModelCatalog.defaultModel.withRuntimeSettings()
        if (registry.selectedModelId != fallback.id) {
            registry = registry.copy(selectedModelId = fallback.id)
            saveRegistry()
        }
        return fallback
    }

    fun selectModel(id: String) {
        require(availableModels().any { it.id == id }) { "Unknown model: $id" }
        if (registry.selectedModelId == id) return
        registry = registry.copy(selectedModelId = id)
        saveRegistry()
    }

    fun setNativeMaxTokens(modelId: String, value: Int) {
        val model = availableModels().firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown model: $modelId")
        val upperBound = model.contextWindowTokens.coerceIn(1, MAX_NATIVE_MAX_TOKENS)
        val lowerBound = minOf(MIN_NATIVE_MAX_TOKENS, upperBound)
        val bounded = value.coerceIn(lowerBound, upperBound)
        registry = registry.copy(nativeMaxTokens = registry.nativeMaxTokens + (modelId to bounded))
        saveRegistry()
    }

    fun hasHuggingFaceToken(): Boolean {
        val plaintext = decryptHuggingFaceTokenBytes() ?: return false
        return try {
            plaintext.any { byte -> byte.toInt() > 0x20 }
        } finally {
            plaintext.fill(0)
        }
    }

    fun setHuggingFaceToken(token: String?) {
        val normalized = token?.trim().orEmpty()
        if (normalized.isEmpty()) {
            prefs.edit()
                .remove(PREF_HF_TOKEN_CIPHERTEXT)
                .remove(PREF_HF_TOKEN_IV)
                .apply()
            return
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateHuggingFaceTokenKey())
        val plaintext = normalized.toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        prefs.edit()
            .putString(
                PREF_HF_TOKEN_CIPHERTEXT,
                Base64.getEncoder().encodeToString(ciphertext),
            )
            .putString(PREF_HF_TOKEN_IV, Base64.getEncoder().encodeToString(cipher.iv))
            .apply()
    }

    suspend fun addCustomModel(directUrl: String): ModelDescriptor = withContext(Dispatchers.IO) {
        val parsed = parseDirectModelUrl(directUrl)
        val expectedBytes = resolveExpectedBytes(parsed.normalizedUrl)
        val existing = availableModels().firstOrNull { it.downloadUrl == parsed.normalizedUrl }
        if (existing != null) {
            selectModel(existing.id)
            return@withContext existing
        }

        val model = ModelDescriptor(
            id = stableCustomModelId(parsed),
            displayName = displayNameFor(parsed.filename),
            description = "Custom LiteRT-LM model · ${parsed.owner}/${parsed.repository}",
            downloadUrl = parsed.normalizedUrl,
            filename = sanitizeFilename(parsed.filename),
            expectedBytes = expectedBytes,
            contextWindowTokens = DEFAULT_NATIVE_MAX_TOKENS,
            nativeMaxTokens = DEFAULT_NATIVE_MAX_TOKENS,
            isCustom = true
        )
        registry = registry.copy(
            selectedModelId = model.id,
            customModels = (registry.customModels.filterNot { it.id == model.id } + model)
        )
        saveRegistry()
        model.withRuntimeSettings()
    }

    fun downloadModel(model: ModelDescriptor): Flow<DownloadProgress> = flow {
        require(model.expectedBytes > 0L) { "Model expected size must be positive" }
        val finalFile = modelFile(model)
        val partialFile = partialFile(model)
        finalFile.parentFile?.mkdirs()

        if (finalFile.exists()) {
            if (finalFile.length() == model.expectedBytes) {
                emit(doneProgress(model.expectedBytes))
                return@flow
            }
            if (!finalFile.delete()) {
                throw IllegalStateException("Cannot remove invalid model file ${finalFile.name}")
            }
        }

        var existingBytes = if (partialFile.exists()) partialFile.length() else 0L
        if (existingBytes > model.expectedBytes) {
            if (!partialFile.delete()) {
                throw IllegalStateException("Cannot remove oversized partial file ${partialFile.name}")
            }
            existingBytes = 0L
        }

        if (existingBytes == model.expectedBytes) {
            installValidated(model, partialFile, finalFile)
            emit(doneProgress(model.expectedBytes))
            return@flow
        }

        emitProgress(existingBytes, model.expectedBytes, 0f)
        downloadSequential(this, model, partialFile, existingBytes)

        val downloadedBytes = partialFile.length()
        if (downloadedBytes != model.expectedBytes) {
            throw IllegalStateException(
                "Downloaded ${model.displayName} has $downloadedBytes bytes; expected ${model.expectedBytes}."
            )
        }
        installValidated(model, partialFile, finalFile)
        emit(doneProgress(model.expectedBytes))
    }.flowOn(Dispatchers.IO)

    fun deleteModel(id: String) {
        val model = availableModels().firstOrNull { it.id == id } ?: return
        modelFile(model).delete()
        partialFile(model).delete()
    }

    private fun availableModels(): List<ModelDescriptor> {
        val builtInIds = ModelCatalog.builtIns.mapTo(mutableSetOf()) { it.id }
        val custom = registry.customModels
            .filterNot { it.id in builtInIds }
            .map { it.withRuntimeSettings() }
        return ModelCatalog.builtIns.map { it.withRuntimeSettings() } + custom
    }

    private fun artifactFor(model: ModelDescriptor): ModelArtifact {
        val finalFile = modelFile(model)
        val partial = partialFile(model)
        val installedBytes = if (finalFile.exists()) finalFile.length() else 0L
        return ModelArtifact(
            model = model,
            path = finalFile.absolutePath,
            installedBytes = installedBytes,
            partialBytes = if (partial.exists()) partial.length() else 0L,
            isInstalled = installedBytes == model.expectedBytes
        )
    }

    private fun parseDirectModelUrl(input: String): DirectModelUrl {
        val candidate = input.trim()
        require(candidate.isNotEmpty()) {
            "Enter a direct HTTPS Hugging Face .litertlm URL. Repository URLs are ambiguous."
        }
        val uri = try {
            URI(candidate)
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "Use a direct https://huggingface.co/<owner>/<repo>/resolve/<revision>/<file>.litertlm URL.",
                error
            )
        }
        require(
            uri.scheme?.equals("https", ignoreCase = true) == true &&
                uri.host?.equals("huggingface.co", ignoreCase = true) == true &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.fragment == null
        ) {
            "Use a direct HTTPS URL hosted on huggingface.co; repository URLs are ambiguous."
        }

        val path = uri.path.orEmpty()
        val segments = path.trim('/').split('/')
        require(
            segments.size >= 5 &&
                segments.none { it.isBlank() || it == "." || it == ".." } &&
                (segments[2] == "resolve" || segments[2] == "blob")
        ) {
            "Use a direct Hugging Face file URL with /resolve/<revision>/<file>.litertlm; repository URLs are ambiguous."
        }
        val owner = segments[0]
        val repository = segments[1]
        val revision = segments[3]
        val filePath = segments.drop(4).joinToString("/")
        val filename = filePath.substringAfterLast('/')
        require(filename.endsWith(".litertlm", ignoreCase = true)) {
            "Direct Hugging Face URL must point to a .litertlm file."
        }
        require(owner != "." && owner != ".." && repository != "." && repository != "..") {
            "Hugging Face owner and repository are invalid."
        }

        val rawPath = uri.rawPath ?: path
        val normalizedRawPath = rawPath.replaceFirst("/blob/", "/resolve/")
        val pathStart = candidate.indexOf(rawPath)
        val normalizedUrl = if (pathStart >= 0) {
            candidate.substring(0, pathStart) + normalizedRawPath + candidate.substring(pathStart + rawPath.length)
        } else {
            candidate.replaceFirst("/blob/", "/resolve/")
        }
        return DirectModelUrl(
            normalizedUrl = normalizedUrl,
            owner = owner,
            repository = repository,
            revision = revision,
            filePath = filePath,
            filename = filename
        )
    }

    private fun resolveExpectedBytes(url: String): Long {
        val request = requestBuilder(url)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Could not inspect model size: HTTP ${response.code} ${response.message}".trim()
                )
            }
            val contentRange = parseContentRange(response.header("Content-Range"))
            val totalBytes = when {
                contentRange != null -> {
                    require(contentRange.start == 0L && contentRange.endInclusive == 0L) {
                        "Hugging Face returned an invalid size range."
                    }
                    contentRange.total
                }
                response.code == 200 -> response.body?.contentLength() ?: -1L
                else -> -1L
            }
            require(totalBytes > 0L) {
                "Could not determine the exact .litertlm size; Hugging Face must support a Range 0-0 request."
            }
            return totalBytes
        }
    }

    private suspend fun downloadSequential(
        collector: FlowCollector<DownloadProgress>,
        model: ModelDescriptor,
        partialFile: File,
        initialBytes: Long
    ) {
        var existingBytes = initialBytes
        var response = openDownloadResponse(model.downloadUrl, existingBytes)
        if (existingBytes > 0L && response.code == 200) {
            response.close()
            if (!partialFile.delete()) {
                throw IllegalStateException("Cannot restart the partial model download safely")
            }
            existingBytes = 0L
            response = openDownloadResponse(model.downloadUrl, 0L)
        }

        response.use { currentResponse ->
            validateDownloadResponse(currentResponse, existingBytes, model.expectedBytes)
            val body = currentResponse.body ?: throw IllegalStateException("Empty model download response")
            val buffer = ByteArray(BUFFER_SIZE)
            var downloadedBytes = existingBytes
            var lastProgressTime = System.currentTimeMillis()
            var lastProgressBytes = downloadedBytes

            FileOutputStream(partialFile, existingBytes > 0L).use { output ->
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime >= PROGRESS_INTERVAL_MILLIS) {
                            val elapsed = now - lastProgressTime
                            val speed = speedMbps(downloadedBytes - lastProgressBytes, elapsed)
                            collector.emitProgress(downloadedBytes, model.expectedBytes, speed)
                            lastProgressTime = now
                            lastProgressBytes = downloadedBytes
                        }
                    }
                }
            }
        }
    }

    private fun openDownloadResponse(url: String, existingBytes: Long) =
        client.newCall(
            requestBuilder(url)
                .header("Range", "bytes=$existingBytes-")
                .build()
        ).execute()

    private fun validateDownloadResponse(response: okhttp3.Response, existingBytes: Long, expectedBytes: Long) {
        if (!response.isSuccessful) {
            throw IllegalStateException("Model download failed: HTTP ${response.code} ${response.message}".trim())
        }
        if (response.code == 206) {
            val range = parseContentRange(response.header("Content-Range"))
                ?: throw IllegalStateException("Model server returned 206 without a valid Content-Range")
            require(range.start == existingBytes && range.total == expectedBytes) {
                "Model server returned an unexpected byte range; partial data was not appended."
            }
            val rangeLength = range.endInclusive - range.start + 1L
            val contentLength = response.body?.contentLength() ?: -1L
            require(contentLength < 0L || contentLength == rangeLength) {
                "Model server returned an unexpected response length; partial data was not appended."
            }
        } else if (existingBytes > 0L) {
            throw IllegalStateException("Model server ignored the resume range; partial data was not appended.")
        }
    }

    private suspend fun FlowCollector<DownloadProgress>.emitProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        speed: Float
    ) {
        val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        val eta = if (speed > 0f) {
            (remaining / 1024f / 1024f / speed).toInt().coerceAtLeast(0)
        } else {
            0
        }
        emit(
            DownloadProgress(
                progressPercent = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
                downloadedMb = downloadedBytes / 1024f / 1024f,
                totalMb = totalBytes / 1024f / 1024f,
                speedMbps = speed,
                etaSeconds = eta
            )
        )
    }

    private fun doneProgress(totalBytes: Long): DownloadProgress {
        val totalMb = totalBytes / 1024f / 1024f
        return DownloadProgress(
            progressPercent = 1f,
            downloadedMb = totalMb,
            totalMb = totalMb,
            speedMbps = 0f,
            etaSeconds = 0,
            isDone = true
        )
    }

    private fun speedMbps(bytes: Long, elapsedMillis: Long): Float =
        if (elapsedMillis > 0L) (bytes / 1024f / 1024f) / (elapsedMillis / 1_000f) else 0f

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let {
            Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$").matchEntire(it)
        } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (end < start || total <= end) return null
        return ContentRange(start, end, total)
    }

    private fun installValidated(model: ModelDescriptor, partial: File, finalFile: File) {
        require(partial.exists() && partial.length() == model.expectedBytes) {
            "Downloaded ${model.displayName} failed exact size validation."
        }
        if (finalFile.exists() && !finalFile.delete()) {
            throw IllegalStateException("Cannot replace the existing model file ${finalFile.name}")
        }
        require(partial.parentFile?.canonicalFile == finalFile.parentFile?.canonicalFile) {
            "Model files must be installed within one directory."
        }
        if (!partial.renameTo(finalFile)) {
            throw IllegalStateException("Cannot install model file ${finalFile.name}")
        }
        require(finalFile.length() == model.expectedBytes) {
            "Installed ${model.displayName} failed exact size validation."
        }
    }

    private fun modelFile(model: ModelDescriptor): File =
        File(modelDirectory(model), sanitizeFilename(model.filename))

    private fun partialFile(model: ModelDescriptor): File =
        File(modelDirectory(model), "${sanitizeFilename(model.filename)}.part")

    private fun modelDirectory(model: ModelDescriptor): File {
        val root = context.getExternalFilesDir(MODEL_DIRECTORY) ?: File(context.filesDir, MODEL_DIRECTORY)
        return File(root, sanitizeFilename(model.id))
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "LiteRT-Server-Android/1.0")
        if (URI(url).host.equals(HUGGING_FACE_HOST, ignoreCase = true)) {
            decryptHuggingFaceToken()?.let { token ->
                builder.header("Authorization", "Bearer $token")
            }
        }
        return builder
    }

    private fun decryptHuggingFaceToken(): String? {
        val plaintext = decryptHuggingFaceTokenBytes() ?: return null
        return try {
            plaintext.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decryptHuggingFaceTokenBytes(): ByteArray? {
        val encodedCiphertext = prefs.getString(PREF_HF_TOKEN_CIPHERTEXT, null) ?: return null
        val encodedIv = prefs.getString(PREF_HF_TOKEN_IV, null) ?: return null
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(HF_TOKEN_KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.getDecoder().decode(encodedIv)),
            )
            cipher.doFinal(Base64.getDecoder().decode(encodedCiphertext))
        }.getOrNull()
    }

    private fun getOrCreateHuggingFaceTokenKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(HF_TOKEN_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                HF_TOKEN_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadRegistry(): RegistryState {
        val serialized = prefs.getString(PREF_REGISTRY, null) ?: return RegistryState()
        return runCatching { json.decodeFromString<RegistryState>(serialized) }
            .getOrDefault(RegistryState())
    }

    private fun saveRegistry() {
        prefs.edit().putString(PREF_REGISTRY, json.encodeToString(registry)).apply()
    }

    private fun ModelDescriptor.withRuntimeSettings(): ModelDescriptor {
        val configured = registry.nativeMaxTokens[id] ?: nativeMaxTokens
        val upperBound = contextWindowTokens.coerceIn(1, MAX_NATIVE_MAX_TOKENS)
        val lowerBound = minOf(MIN_NATIVE_MAX_TOKENS, upperBound)
        return copy(nativeMaxTokens = configured.coerceIn(lowerBound, upperBound))
    }

    private fun stableCustomModelId(model: DirectModelUrl): String =
        "hf-${sanitizeFilename("${model.owner}-${model.repository}-${model.revision}-${model.filePath}".lowercase(Locale.US))}"

    private fun displayNameFor(filename: String): String =
        filename.removeSuffix(".litertlm").removeSuffix(".LITERTLM")
            .split('-', '_', '.', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
            .ifBlank { "Custom LiteRT-LM model" }

    private fun sanitizeFilename(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(180)
            .ifBlank { "model" }
}
