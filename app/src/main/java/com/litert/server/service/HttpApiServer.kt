package com.litert.server.service

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
import kotlin.coroutines.cancellation.CancellationException
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
    private val modelId: String
) {
    private var server: ApplicationEngine? = null

    var port: Int = 0
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun start(): Int {
        server?.let { return port }

        var lastFailure: Throwable? = null
        for (candidatePort in 8080..8082) {
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
                                request.temperature
                                    ?: if (toolsEnabled(request)) 0.0 else DEFAULT_TEMPERATURE
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

                            val prompt = buildPrompt(request)
                            if (request.stream) {
                                streamCompletion(call, request, prompt, temperature)
                            } else {
                                completeRequest(call, request, prompt, temperature)
                            }
                        }
                    }
                }
            }

            try {
                candidate.start(wait = false)
                server = candidate
                port = candidatePort
                return candidatePort
            } catch (failure: Throwable) {
                lastFailure = failure
                runCatching { candidate.stop(0, 0) }
            }
        }

        throw IllegalStateException("Could not bind to a localhost port", lastFailure)
    }

    private suspend fun completeRequest(
        call: ApplicationCall,
        request: OaiChatRequest,
        prompt: String,
        temperature: Double
    ) {
        val output = StringBuilder()
        val usage = engine.generate(prompt, temperature) { chunk ->
            output.append(chunk)
        }
        val toolCall = parseToolCall(output.toString(), request)
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
                usage = usage.toOaiUsage(),
            )
        )
    }

    private suspend fun streamCompletion(
        call: ApplicationCall,
        request: OaiChatRequest,
        prompt: String,
        temperature: Double,
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
                    engine.generate(prompt, temperature) { chunk ->
                        output.append(chunk)
                    }.toOaiUsage()
                val toolCall = parseToolCall(output.toString(), request)
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

    private fun buildPrompt(request: OaiChatRequest): String {
        val sections = ArrayList<String>(request.messages.size + 3)
        val toolNameById =
            request.messages
                .flatMap { it.toolCalls.orEmpty() }
                .associate { it.id to it.function.name }
        val completedToolNames =
            request.messages
                .filter { it.role.equals("tool", ignoreCase = true) }
                .mapNotNull { it.toolCallId?.let(toolNameById::get) }
                .distinct()
        if (toolsEnabled(request)) {
            val definitions =
                request.tools
                    .orEmpty()
                    .filter { it.type.equals("function", ignoreCase = true) }
                    .joinToString("\n", transform = ::compactToolDefinition)
            sections += "Available function tools:\n$definitions"
        }

        request.messages.forEach { message ->
            val role = message.role.trim().ifEmpty { "user" }
            val displayRole =
                if (role.equals("tool", ignoreCase = true)) {
                    val toolName = message.toolCallId?.let(toolNameById::get) ?: "unknown"
                    "tool result for $toolName (completed)"
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

        if (completedToolNames.isNotEmpty()) {
            sections +=
                "Completed tools: ${completedToolNames.joinToString(", ")}. " +
                    "Continue with the next requested action; do not repeat a completed tool " +
                    "unless the user explicitly requested repetition."
        }

        if (toolsEnabled(request)) {
            sections +=
                "To call a tool, output only " +
                    "<tool_call>{\"name\":\"tool_name\",\"arguments\":{...}}</tool_call>. " +
                    "Call exactly one tool at a time. Do not use Markdown around a tool call. " +
                    "When no tool is needed, answer normally."
        }
        sections += "assistant:"
        return sections.joinToString("\n\n")
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

    fun stop() {
        val active = server ?: return
        server = null
        port = 0
        active.stop(1000, 5000)
    }

    private companion object {
        const val DEFAULT_TEMPERATURE = 0.7
        const val TOOL_CALL_MARKER = "<tool_call>"
    }
}
