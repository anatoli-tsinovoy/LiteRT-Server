package com.litert.server.service

import android.util.Log

import com.litert.server.data.DEFAULT_SERVER_PORT
import com.litert.server.data.MAX_SERVER_PORT
import com.litert.server.data.MIN_SERVER_PORT
import com.litert.server.download.ToolPromptProfile
import com.litert.server.data.OaiChatRequest
import com.litert.server.data.OaiChatResponse
import com.litert.server.data.OaiChoice
import com.litert.server.data.OaiDelta
import com.litert.server.data.OaiError
import com.litert.server.data.OaiErrorResponse
import com.litert.server.data.OaiFunctionCall
import com.litert.server.data.OaiHealthResponse
import com.litert.server.data.OaiMessage
import com.litert.server.data.OaiModelEntry
import com.litert.server.data.OaiModelsResponse
import com.litert.server.data.OaiStreamChunk
import com.litert.server.data.OaiStreamFunctionCall
import com.litert.server.data.OaiStreamToolCall
import com.litert.server.data.OaiToolCall
import com.litert.server.data.OaiTool
import com.litert.server.data.OaiStreamChoice
import com.litert.server.data.OaiUsage
import com.litert.server.engine.GenerationUsage
import com.litert.server.engine.LiteRTEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class HttpApiServer(
    private val engine: LiteRTEngine,
    private val apiToken: String,
    private val modelId: String,
    private val toolPromptProfile: ToolPromptProfile = ToolPromptProfile.TAGGED_JSON,
    private val configuredPort: Int = DEFAULT_SERVER_PORT
) {
    init {
        require(configuredPort in MIN_SERVER_PORT..MAX_SERVER_PORT) {
            "Server port must be between $MIN_SERVER_PORT and $MAX_SERVER_PORT"
        }
    }
    private var server: ApplicationEngine? = null
    private val generationJobs = ConcurrentHashMap.newKeySet<Job>()
    private val acceptingGenerations = AtomicBoolean(false)
    var port: Int = 0
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun start(): Int {
        server?.let { return port }

        val candidatePort = configuredPort
        val candidate = embeddedServer(CIO, host = "127.0.0.1", port = candidatePort) {
                install(ContentNegotiation) {
                    json(json)
                }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        if (cause is CancellationException) throw cause
                        if (cause is ContentTransformationException || cause is SerializationException) {
                            call.respondError(
                                status = HttpStatusCode.BadRequest,
                                message = "Invalid JSON request body"
                            )
                        } else {
                            call.respondError(
                                status = HttpStatusCode.InternalServerError,
                                message = "Internal server error",
                                type = "server_error",
                                code = "internal_error"
                            )
                        }
                    }
                }

                routing {
                    get("/health") {
                        call.respond(
                            OaiHealthResponse(
                                status = "ok",
                                model = modelId,
                                ready = engine.isReady,
                                gpu = engine.backend.equals("GPU", ignoreCase = true),
                                backendError = engine.gpuFallbackReason
                            )
                        )
                    }

                    route("/v1") {
                        get("/models") {
                            if (!call.requireApiToken()) return@get
                            call.respond(
                                OaiModelsResponse(
                                    data = listOf(
                                        OaiModelEntry(
                                            id = modelId,
                                            created = System.currentTimeMillis() / 1000
                                        )
                                    )
                                )
                            )
                        }

                        post("/chat/completions") {
                            if (!call.requireApiToken()) return@post

                            val request = try {
                                call.receive<OaiChatRequest>()
                            } catch (cause: Throwable) {
                                if (cause is CancellationException) throw cause
                                call.respondError(
                                    status = HttpStatusCode.BadRequest,
                                    message = "Invalid JSON request body"
                                )
                                return@post
                            }

                            if (request.model != modelId) {
                                call.respondError(
                                    status = HttpStatusCode.NotFound,
                                    message = "The model '${request.model}' does not exist",
                                    param = "model",
                                    code = "model_not_found"
                                )
                                return@post
                            }
                            if (request.messages.isEmpty()) {
                                call.respondError(
                                    status = HttpStatusCode.BadRequest,
                                    message = "At least one message is required",
                                    param = "messages"
                                )
                                return@post
                            }
                            val temperature =
                                if (usesStrictToolProtocol() && toolsEnabled(request)) {
                                    0.0
                                } else {
                                    request.temperature
                                        ?: if (toolsEnabled(request)) 0.0 else DEFAULT_TEMPERATURE
                                }
                            if (!temperature.isFinite() || temperature < 0.0 || temperature > 2.0) {
                                call.respondError(
                                    status = HttpStatusCode.BadRequest,
                                    message = "temperature must be between 0 and 2",
                                    param = "temperature"
                                )
                                return@post
                            }
                            if (!engine.isReady) {
                                call.respondError(
                                    status = HttpStatusCode.ServiceUnavailable,
                                    message = "The model engine is not ready",
                                    type = "server_error",
                                    code = "engine_not_ready"
                                )
                                return@post
                            }

                            val strictToolResult = strictToolResultToRelay(request)
                            val prompt = buildPrompt(request, strictToolResult)
                            if (!acceptingGenerations.get()) {
                                call.respondError(
                                    status = HttpStatusCode.ServiceUnavailable,
                                    message = "The server is stopping",
                                    type = "server_error",
                                    code = "server_stopping"
                                )
                                return@post
                            }
                            val generationJob = coroutineContext[Job]
                            if (generationJob != null) {
                                generationJobs.add(generationJob)
                            }
                            if (!acceptingGenerations.get()) {
                                if (generationJob != null) {
                                    generationJobs.remove(generationJob)
                                }
                                call.respondError(
                                    status = HttpStatusCode.ServiceUnavailable,
                                    message = "The server is stopping",
                                    type = "server_error",
                                    code = "server_stopping"
                                )
                                return@post
                            }
                            try {
                                if (request.stream) {
                                    streamCompletion(
                                        call,
                                        request,
                                        prompt,
                                        temperature,
                                        strictToolResult
                                    )
                                } else {
                                    completeRequest(
                                        call,
                                        request,
                                        prompt,
                                        temperature,
                                        strictToolResult
                                    )
                                }
                            } finally {
                                if (generationJob != null) {
                                    generationJobs.remove(generationJob)
                                }
                            }
                        }
                    }
                }
            }

            try {
                candidate.start(wait = false)
                server = candidate
                port = candidatePort
                acceptingGenerations.set(true)
                return candidatePort
            } catch (failure: Throwable) {
                runCatching { candidate.stop(0, 0) }
                throw IllegalStateException(
                    "Could not bind to 127.0.0.1:$candidatePort",
                    failure
                )
            }
    }

    private suspend fun completeRequest(
        call: ApplicationCall,
        request: OaiChatRequest,
        prompt: String,
        temperature: Double,
        strictToolResult: String?,
    ) {
        val output = StringBuilder()
        val usage =
            if (strictToolResult != null) {
                output.append(strictToolResult)
                OaiUsage(promptTokens = 0, completionTokens = 0, totalTokens = 0)
            } else {
                engine.generate(prompt, temperature) { chunk ->
                    output.append(chunk)
                }.toOaiUsage()
            }
        val toolCall =
            if (strictToolResult == null) parseToolCall(output.toString(), request) else null
        val message =
            if (toolCall != null) {
                OaiMessage(
                    role = "assistant",
                    toolCalls =
                        listOf(
                            OaiToolCall(
                                id = toolCallId(),
                                function =
                                    OaiFunctionCall(
                                        name = toolCall.name,
                                        arguments = toolCall.arguments,
                                    ),
                            )
                        ),
                )
            } else {
                OaiMessage(role = "assistant", content = output.toString())
            }
        call.respond(
            OaiChatResponse(
                id = requestId(),
                created = System.currentTimeMillis() / 1000,
                model = modelId,
                choices =
                    listOf(
                        OaiChoice(
                            index = 0,
                            message = message,
                            finishReason = if (toolCall != null) "tool_calls" else "stop",
                        )
                    ),
                usage = usage,
            )
        )
    }

    private suspend fun streamCompletion(
        call: ApplicationCall,
        request: OaiChatRequest,
        prompt: String,
        temperature: Double,
        strictToolResult: String?,
    ) {
        val id = requestId()
        val created = System.currentTimeMillis() / 1000
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            suspend fun writeChunk(chunk: OaiStreamChunk) {
                write("data: ${json.encodeToString(chunk)}\n\n")
                flush()
            }

            writeChunk(
                OaiStreamChunk(
                    id = id,
                    created = created,
                    model = modelId,
                    choices =
                        listOf(
                            OaiStreamChoice(
                                index = 0,
                                delta = OaiDelta(role = "assistant"),
                            )
                        ),
                )
            )

            try {
                val output = StringBuilder()
                val usage =
                    if (strictToolResult != null) {
                        output.append(strictToolResult)
                        OaiUsage(promptTokens = 0, completionTokens = 0, totalTokens = 0)
                    } else {
                        engine.generate(prompt, temperature) { chunk ->
                            output.append(chunk)
                        }.toOaiUsage()
                    }
                val toolCall =
                    if (strictToolResult == null) {
                        parseToolCall(output.toString(), request)
                    } else {
                        null
                    }
                val delta =
                    if (toolCall != null) {
                        OaiDelta(
                            toolCalls =
                                listOf(
                                    OaiStreamToolCall(
                                        index = 0,
                                        id = toolCallId(),
                                        type = "function",
                                        function =
                                            OaiStreamFunctionCall(
                                                name = toolCall.name,
                                                arguments = toolCall.arguments,
                                            ),
                                    )
                                )
                        )
                    } else {
                        OaiDelta(content = output.toString())
                    }
                writeChunk(
                    OaiStreamChunk(
                        id = id,
                        created = created,
                        model = modelId,
                        choices =
                            listOf(
                                OaiStreamChoice(
                                    index = 0,
                                    delta = delta,
                                )
                            ),
                    )
                )
                writeChunk(
                    OaiStreamChunk(
                        id = id,
                        created = created,
                        model = modelId,
                        choices =
                            listOf(
                                OaiStreamChoice(
                                    index = 0,
                                    delta = OaiDelta(),
                                    finishReason = if (toolCall != null) "tool_calls" else "stop",
                                )
                            ),
                    )
                )
                writeChunk(
                    OaiStreamChunk(
                        id = id,
                        created = created,
                        model = modelId,
                        choices = emptyList(),
                        usage = usage,
                    )
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                val error =
                    OaiErrorResponse(
                        error =
                            OaiError(
                                message =
                                    cause.message?.takeIf { it.isNotBlank() }
                                        ?: "Generation failed",
                                type = "server_error",
                                code = "generation_error",
                            )
                    )
                write("data: ${json.encodeToString(error)}\n\n")
                flush()
            }

            write("data: [DONE]\n\n")
            flush()
        }
    }

    private fun buildPrompt(request: OaiChatRequest, strictToolResult: String?): String {
        val sections = ArrayList<String>(request.messages.size + 3)
        val toolsEnabled = toolsEnabled(request)
        val strictToolProtocol = toolsEnabled && usesStrictToolProtocol()
        val hasStrictToolResult = strictToolResult != null
        val toolNameById =
            request.messages
                .flatMap { it.toolCalls.orEmpty() }
                .associate { it.id to it.function.name }
        val completedToolNames =
            request.messages
                .filter { it.role.equals("tool", ignoreCase = true) }
                .mapNotNull { it.toolCallId?.let(toolNameById::get) }
                .distinct()
        val strictToolNamesByMessageIndex = mutableMapOf<Int, String>()
        if (usesStrictToolProtocol()) {
            val assistantToolNamesById = mutableMapOf<String, String>()
            request.messages.forEachIndexed { index, message ->
                if (message.role.equals("assistant", ignoreCase = true)) {
                    message.toolCalls.orEmpty().forEach { call ->
                        assistantToolNamesById[call.id] = call.function.name
                    }
                } else if (message.role.equals("tool", ignoreCase = true)) {
                    val toolCallId = message.toolCallId
                    if (toolCallId != null) {
                        assistantToolNamesById[toolCallId]?.let { toolName ->
                            strictToolNamesByMessageIndex[index] = toolName
                        }
                    }
                }
            }
        }
        if (toolsEnabled && !(strictToolProtocol && hasStrictToolResult)) {
            val definitions =
                request.tools
                    .orEmpty()
                    .filter { it.type.equals("function", ignoreCase = true) }
                    .joinToString("\n", transform = ::compactToolDefinition)
            sections += "Available function tools:\n$definitions"
        }

        request.messages.forEachIndexed { messageIndex, message ->
            val role = message.role.trim().ifEmpty { "user" }
            val displayRole =
                if (role.equals("tool", ignoreCase = true)) {
                    val toolName =
                        if (usesStrictToolProtocol()) {
                            strictToolNamesByMessageIndex[messageIndex]
                        } else {
                            message.toolCallId?.let(toolNameById::get)
                        }
                    when {
                        toolName != null -> "tool result for $toolName (completed)"
                        usesStrictToolProtocol() -> "tool result"
                        else -> "tool result for unknown (completed)"
                    }
                } else {
                    role
                }
            val content = message.content.toPromptText().trim()
            val toolCalls =
                message.toolCalls.orEmpty().joinToString("\n") { call ->
                    "${call.function.name}(${call.function.arguments})"
                }
            val body =
                listOf(content, toolCalls.takeIf { it.isNotBlank() })
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            sections += if (body.isNotBlank()) "$displayRole: $body" else "$displayRole:"
        }

        when {
            strictToolProtocol && hasStrictToolResult -> {
                sections +=
                    "A tool has already executed. Your entire response must be only the exact text " +
                        "from the completed tool-result message above, then stop. Copy every character; " +
                        "repeated words and path components such as /data/data are intentional and " +
                        "must remain repeated. If the tool result is an error, repeat that error " +
                        "exactly; do not invent a successful result, retry, call another tool, or " +
                        "infer file contents or command output. Do not output Markdown or a JSON " +
                        "tool-call object."
            }
            strictToolProtocol -> {
                sections +=
                    "You have executable function tools. If the user asks to read a file, run a " +
                        "command, or obtain information available only through a listed tool, you " +
                        "must call the matching tool and must not invent the result. Copy every " +
                        "argument value character-for-character from the user's request. Keep relative " +
                        "paths relative. Never prepend, normalize, or append punctuation to a path. " +
                        "Repeated path components such as /data/data are intentional. Include required " +
                        "arguments and arguments explicitly requested by the user; omit all other " +
                        "optional arguments. To call a tool, output exactly one JSON object in this " +
                        "form: {\"name\":\"tool_name\",\"arguments\":{\"argument_name\":\"value\"}}. " +
                        "Use the exact tool and argument names listed above. Output no Markdown, " +
                        "explanation, or other text around the JSON object. Call exactly one tool at " +
                        "a time."
                if (request.tools.orEmpty().any { it.function.name == "read" }) {
                    sections +=
                        "Read example: if the requested path is local.properties, output exactly " +
                            "{\"name\":\"read\",\"arguments\":{\"path\":\"local.properties\"}}."
                }
                if (request.tools.orEmpty().any { it.function.name == "bash" }) {
                    sections +=
                        "Bash example: if asked to run ls in /tmp, output exactly " +
                            "{\"name\":\"bash\",\"arguments\":{\"i\":\"Listing files\"," +
                            "\"command\":\"ls\",\"cwd\":\"/tmp\"}}. Copy the requested command and " +
                            "cwd exactly; do not add env, timeout, pty, or async unless requested."
                }
            }
            else -> {
                if (completedToolNames.isNotEmpty()) {
                    sections +=
                        "Completed tools: ${completedToolNames.joinToString(", ")}. " +
                            "Continue with the next requested action; do not repeat a completed tool " +
                            "unless the user explicitly requested repetition."
                }
                if (toolsEnabled) {
                    sections +=
                        "To call a tool, output only " +
                            "<tool_call>{\"name\":\"tool_name\",\"arguments\":{...}}</tool_call>. " +
                            "Call exactly one tool at a time. Do not use Markdown around a tool call. " +
                            "When no tool is needed, answer normally."
                }
            }
        }
        sections += "assistant:"
        return sections.joinToString("\n\n")
    }

    private fun usesStrictToolProtocol(): Boolean =
        toolPromptProfile == ToolPromptProfile.STRICT_JSON_RELAY

    private fun strictToolResultToRelay(request: OaiChatRequest): String? {
        if (!usesStrictToolProtocol()) return null
        val finalMessage = request.messages.lastOrNull() ?: return null
        if (!finalMessage.role.equals("tool", ignoreCase = true)) return null
        val toolCallId = finalMessage.toolCallId ?: return null
        val finalMessageIndex = request.messages.lastIndex
        for (messageIndex in 0 until finalMessageIndex) {
            val message = request.messages[messageIndex]
            if (
                message.role.equals("assistant", ignoreCase = true) &&
                    message.toolCalls.orEmpty().any { it.id == toolCallId }
            ) {
                return finalMessage.content.toPromptText()
            }
        }
        return null
    }

    private fun compactToolDefinition(tool: OaiTool): String {
        val schema = tool.function.parameters as? JsonObject
        val properties = schema?.get("properties") as? JsonObject
        val required =
            (schema?.get("required") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
        val parameters =
            properties
                ?.entries
                ?.joinToString(", ") { (name, definition) ->
                    val type =
                        ((definition as? JsonObject)?.get("type") as? JsonPrimitive)
                            ?.contentOrNull
                            ?: "value"
                    "$name:$type${if (name in required) " required" else ""}"
                }
                .orEmpty()
        val description =
            tool.function.description
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(120)
                ?.takeIf { it.isNotEmpty() }
                ?.let { " — $it" }
                .orEmpty()
        return "- ${tool.function.name}($parameters)$description"
    }

    private fun toolsEnabled(request: OaiChatRequest): Boolean {
        val choice = request.toolChoice as? JsonPrimitive
        return request.tools.orEmpty().any { it.type.equals("function", ignoreCase = true) } &&
            !choice?.contentOrNull.equals("none", ignoreCase = true)
    }

    private fun parseToolCall(output: String, request: OaiChatRequest): ParsedToolCall? {
        if (!toolsEnabled(request)) return null
        val payload = extractToolCallPayload(output) ?: return null
        val parsed =
            runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                ?: return null
        val name = (parsed["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val allowed =
            request.tools
                .orEmpty()
                .any {
                    it.type.equals("function", ignoreCase = true) && it.function.name == name
                }
        if (!allowed) return null
        val arguments = parsed["arguments"] ?: JsonObject(emptyMap())
        val encodedArguments =
            if (arguments is JsonPrimitive && arguments.isString) {
                arguments.content
            } else {
                arguments.toString()
            }
        return ParsedToolCall(name = name, arguments = encodedArguments)
    }
    private fun extractToolCallPayload(output: String): String? {
        val markerIndex = output.indexOf(TOOL_CALL_MARKER, ignoreCase = true)
        var objectStart =
            output.indexOf(
                '{',
                startIndex =
                    if (markerIndex >= 0) markerIndex + TOOL_CALL_MARKER.length else 0,
            )
        while (objectStart >= 0) {
            val candidate = balancedJsonObject(output, objectStart)
            if (candidate != null) {
                val parsed =
                    runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
                if (parsed?.containsKey("name") == true &&
                    parsed.containsKey("arguments")
                ) {
                    return candidate
                }
            }
            objectStart = output.indexOf('{', startIndex = objectStart + 1)
        }
        return null
    }

    private fun balancedJsonObject(output: String, objectStart: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in objectStart until output.length) {
            val character = output[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return output.substring(objectStart, index + 1)
                }
            }
        }
        return null
    }


    private fun JsonElement?.toPromptText(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> contentOrNull ?: toString()
        is JsonArray -> joinToString("\n") { it.toPromptText() }
        is JsonObject -> {
            val text = this["text"] ?: this["input_text"] ?: this["content"]
            if (text != null) {
                text.toPromptText()
            } else {
                entries.joinToString(", ") { (key, value) -> "$key=${value.toPromptText()}" }
            }
        }
    }

    private suspend fun ApplicationCall.requireApiToken(): Boolean {
        val authorization = request.headers[HttpHeaders.Authorization]
        val fields = authorization?.trim()?.split(Regex("\\s+"), limit = 2)
        val valid = fields != null &&
            fields.size == 2 &&
            fields[0].equals("Bearer", ignoreCase = true) &&
            secureEquals(fields[1], apiToken)
        if (valid) return true

        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondError(
            status = HttpStatusCode.Unauthorized,
            message = "Missing or invalid bearer token",
            type = "authentication_error",
            code = "invalid_api_key"
        )
        return false
    }

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        message: String,
        type: String = "invalid_request_error",
        param: String? = null,
        code: String? = null
    ) {
        respond(
            status,
            OaiErrorResponse(
                error = OaiError(
                    message = message,
                    type = type,
                    param = param,
                    code = code
                )
            )
        )
    }

    private fun secureEquals(left: String, right: String): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        return MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun GenerationUsage.toOaiUsage(): OaiUsage = OaiUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens
    )

    private fun requestId(): String = "chatcmpl-${UUID.randomUUID()}"
    private fun toolCallId(): String = "call_${UUID.randomUUID().toString().replace("-", "")}"

    private data class ParsedToolCall(
        val name: String,
        val arguments: String,
    )

    suspend fun stop(onStage: (String) -> Unit = {}) {
        acceptingGenerations.set(false)
        withContext(NonCancellable + Dispatchers.IO) {
            val active = server
            server = null
            port = 0
            val jobs = generationJobs.toList()

            fun emit(stage: String, detail: String? = null) {
                val suffix = detail?.let { " $it" }.orEmpty()
                Log.i(STOP_STAGE_LOG_TAG, "stage=$stage$suffix")
                try {
                    onStage(stage)
                } catch (_: Throwable) {
                    // Diagnostics must not change cleanup behavior.
                }
            }

            emit(STOP_STAGE_ADMISSION, "jobs=${jobs.size}")

            var cleanupFailure: Throwable? = null
            fun recordFailure(failure: Throwable) {
                val first = cleanupFailure
                if (first == null) {
                    cleanupFailure = failure
                } else if (first !== failure) {
                    first.addSuppressed(failure)
                }
            }

            emit(STOP_STAGE_NATIVE_DRAIN)
            repeat(STOP_CANCEL_ATTEMPTS) { attempt ->
                if (!jobs.any { it.isActive }) return@repeat
                try {
                    engine.cancelActiveConversation()
                } catch (failure: Throwable) {
                    recordFailure(failure)
                }
                if (attempt + 1 < STOP_CANCEL_ATTEMPTS && jobs.any { it.isActive }) {
                    try {
                        delay(STOP_CANCEL_INTERVAL_MILLIS)
                    } catch (failure: Throwable) {
                        recordFailure(failure)
                    }
                }
            }

            jobs.forEach { job ->
                try {
                    job.cancel()
                } catch (failure: Throwable) {
                    recordFailure(failure)
                }
            }
            emit(STOP_STAGE_JOBS_CANCELED)

            emit(STOP_STAGE_KTOR_STOP_CALL)
            try {
                active?.stop(0, STOP_TIMEOUT_MILLIS)
            } catch (failure: Throwable) {
                recordFailure(failure)
            } finally {
                emit(STOP_STAGE_KTOR_STOP_RETURNED)
            }

            emit(STOP_STAGE_JOINS)
            jobs.forEach { job ->
                try {
                    job.join()
                } catch (failure: Throwable) {
                    recordFailure(failure)
                }
            }
            emit(STOP_STAGE_COMPLETED)

            cleanupFailure?.let { throw it }
        }
    }

    private companion object {
        private const val STOP_STAGE_LOG_TAG = "LiteRT-StopStage"
        private const val STOP_STAGE_ADMISSION = "admission closed; jobs captured"
        private const val STOP_STAGE_NATIVE_DRAIN = "native drain"
        private const val STOP_STAGE_JOBS_CANCELED = "jobs canceled"
        private const val STOP_STAGE_KTOR_STOP_CALL = "Ktor listener stop call"
        private const val STOP_STAGE_KTOR_STOP_RETURNED = "Ktor listener stop returned"
        private const val STOP_STAGE_JOINS = "joins"
        private const val STOP_STAGE_COMPLETED = "completed"
        const val DEFAULT_TEMPERATURE = 0.7
        const val TOOL_CALL_MARKER = "<tool_call>"
        const val STOP_TIMEOUT_MILLIS = 1_000L
        const val STOP_CANCEL_INTERVAL_MILLIS = 100L
        const val STOP_CANCEL_ATTEMPTS = 20
    }
}
