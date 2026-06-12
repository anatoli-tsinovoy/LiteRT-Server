package com.litert.server.service

import com.litert.server.DiagnosticsLogger
import com.litert.server.data.ChatRequest
import com.litert.server.data.ChatResponse
import com.litert.server.data.ErrorResponse
import com.litert.server.data.HealthResponse
import com.litert.server.data.OaiChatRequest
import com.litert.server.data.OaiRequestMessage
import com.litert.server.data.OaiChatResponse
import com.litert.server.data.OaiFunctionCall
import com.litert.server.data.OaiChoice
import com.litert.server.data.OaiDelta
import com.litert.server.data.OaiMessage
import com.litert.server.data.OaiModelEntry
import com.litert.server.data.OaiModelsResponse
import com.litert.server.data.OaiStreamChunk
import com.litert.server.data.OaiStreamChoice
import com.litert.server.data.OaiStreamFunctionCall
import com.litert.server.data.OaiStreamToolCall
import com.litert.server.data.OaiTool
import com.litert.server.data.OaiToolCall
import com.litert.server.data.OaiUsage
import com.litert.server.data.RequestLogEntry
import com.litert.server.data.VisionRequest
import com.litert.server.engine.GenerationUsage
import com.litert.server.engine.LiteRTEngine
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.authorization
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class HttpApiServer(
    private val engine: LiteRTEngine,
    private val apiToken: String,
    private val modelId: String,
    private val onRequest: (RequestLogEntry) -> Unit
) {
    private data class ParsedToolCalls(
        val content: String,
        val toolCalls: List<OaiToolCall>
    )

    private var server: ApplicationEngine? = null
    var port: Int = 8080
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val GEMMA_TOOL_CALL_REGEX = Regex("<\\|tool_call>(.*?)<tool_call\\|>", RegexOption.DOT_MATCHES_ALL)
    private val GEMMA_STRING_DELIMITER = "<|\"|>"


    fun start(): Int {
        for (tryPort in 8080..8082) {
            try {
                DiagnosticsLogger.event("HttpApiServer", "Starting server on 127.0.0.1:$tryPort model=$modelId")
                server = embeddedServer(CIO, host = "127.0.0.1", port = tryPort) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                    install(CORS) {
                        anyHost()
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader(HttpHeaders.ContentType)
                    }
                    install(StatusPages) {
                        exception<Throwable> { call, cause ->
                            DiagnosticsLogger.error(
                                "HttpApiServer",
                                "Unhandled ${call.request.httpMethod.value} ${call.request.uri}",
                                cause
                            )
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(error = cause.message ?: "Unknown error", code = 500)
                            )
                        }
                    }

                    routing {

                        // ── health ──────────────────────────────────────────
                        get("/health") {
                            call.respond(
                                HealthResponse(
                                    status = "ok",
                                    model = modelId,
                                    gpu = engine.getBackend() == "GPU",
                                    ready = engine.isReady
                                )
                            )
                        }

                        // ── OpenAI-compatible v1 routes ──────────────────────
                        route("/v1") {

                            get("/models") {
                                if (!call.requireApiToken()) return@get
                                call.respond(
                                    OaiModelsResponse(
                                        data = listOf(
                                            OaiModelEntry(id = modelId)
                                        )
                                    )
                                )
                            }

                            post("/chat/completions") {
                                if (!call.requireApiToken()) return@post
                                if (!engine.isReady) {
                                    call.respond(
                                        HttpStatusCode.ServiceUnavailable,
                                        ErrorResponse("Engine not ready", 503)
                                    )
                                    return@post
                                }

                                val req = call.receive<OaiChatRequest>()
                                val start = System.currentTimeMillis()

                                val prompt = buildPrompt(req)
                                DiagnosticsLogger.event(
                                    "HttpApiServer",
                                    "chat/completions stream=${req.stream} messages=${req.messages.size} promptChars=${prompt.length}"
                                )

                                if (req.stream) {
                                    val reqId = "chatcmpl-${System.currentTimeMillis()}"
                                    var streamStatusCode = 200
                                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                        var completed = false
                                        var streamFinishReason = "stop"
                                        try {
                                            // First chunk carries the role.
                                            val firstChunk = OaiStreamChunk(
                                                id = reqId,
                                                created = System.currentTimeMillis() / 1000,
                                                model = modelId,
                                                choices = listOf(
                                                    OaiStreamChoice(
                                                        index = 0,
                                                        delta = OaiDelta(role = "assistant", content = "")
                                                    )
                                                )
                                            )
                                            write("data: ${json.encodeToString(firstChunk)}\n\n")
                                            flush()

                                            val content = engine.generateStatelessText(prompt).toList().joinToString("")
                                            val parsedToolCalls = parseGemmaToolCalls(content)
                                            if (parsedToolCalls.toolCalls.isNotEmpty()) {
                                                streamFinishReason = "tool_calls"
                                                val chunk = OaiStreamChunk(
                                                    id = reqId,
                                                    created = System.currentTimeMillis() / 1000,
                                                    model = modelId,
                                                    choices = listOf(
                                                        OaiStreamChoice(
                                                            index = 0,
                                                            delta = OaiDelta(
                                                                toolCalls = parsedToolCalls.toolCalls.mapIndexed { index, toolCall ->
                                                                    OaiStreamToolCall(
                                                                        index = index,
                                                                        id = toolCall.id,
                                                                        type = toolCall.type,
                                                                        function = OaiStreamFunctionCall(
                                                                            name = toolCall.function.name,
                                                                            arguments = toolCall.function.arguments
                                                                        )
                                                                    )
                                                                }
                                                            )
                                                        )
                                                    )
                                                )
                                                write("data: ${json.encodeToString(chunk)}\n\n")
                                                flush()
                                            } else if (parsedToolCalls.content.isNotEmpty()) {
                                                val chunk = OaiStreamChunk(
                                                    id = reqId,
                                                    created = System.currentTimeMillis() / 1000,
                                                    model = modelId,
                                                    choices = listOf(
                                                        OaiStreamChoice(
                                                            index = 0,
                                                            delta = OaiDelta(content = parsedToolCalls.content)
                                                        )
                                                    )
                                                )
                                                write("data: ${json.encodeToString(chunk)}\n\n")
                                                flush()
                                            }
                                            completed = true
                                        } catch (t: Throwable) {
                                            streamStatusCode = 500
                                            DiagnosticsLogger.error("HttpApiServer", "chat/completions stream generation failed", t)
                                            val safeMessage = (t.message ?: t.javaClass.name)
                                                .replace('\n', ' ')
                                                .replace('\r', ' ')
                                            val errorChunk = OaiStreamChunk(
                                                id = reqId,
                                                created = System.currentTimeMillis() / 1000,
                                                model = modelId,
                                                choices = listOf(
                                                    OaiStreamChoice(
                                                        index = 0,
                                                        delta = OaiDelta(content = "\n[LiteRT generation failed: $safeMessage]")
                                                    )
                                                )
                                            )
                                            write("data: ${json.encodeToString(errorChunk)}\n\n")
                                        } finally {
                                            val usage = if (completed) engine.getLastGenerationUsage().toOaiUsage() else null
                                            val finishReason = if (completed) streamFinishReason else "stop"
                                            val stopChunk = OaiStreamChunk(
                                                id = reqId,
                                                created = System.currentTimeMillis() / 1000,
                                                model = modelId,
                                                choices = listOf(
                                                    OaiStreamChoice(
                                                        index = 0,
                                                        delta = OaiDelta(),
                                                        finishReason = finishReason
                                                    )
                                                )
                                            )
                                            write("data: ${json.encodeToString(stopChunk)}\n\n")
                                            if (usage != null) {
                                                val usageChunk = OaiStreamChunk(
                                                    id = reqId,
                                                    created = System.currentTimeMillis() / 1000,
                                                    model = modelId,
                                                    choices = emptyList(),
                                                    usage = usage
                                                )
                                                write("data: ${json.encodeToString(usageChunk)}\n\n")
                                            }
                                            write("data: [DONE]\n\n")
                                            flush()
                                            DiagnosticsLogger.event(
                                                "HttpApiServer",
                                                "chat/completions stream closed completed=$completed status=$streamStatusCode"
                                            )
                                        }
                                    }
                                    val ms = System.currentTimeMillis() - start
                                    onRequest(RequestLogEntry(endpoint = "/v1/chat/completions", responseTimeMs = ms, statusCode = streamStatusCode))
                                } else {
                                    val rawContent = engine.generateStatelessText(prompt).toList().joinToString("")
                                    val parsedToolCalls = parseGemmaToolCalls(rawContent)
                                    val ms = System.currentTimeMillis() - start
                                    onRequest(RequestLogEntry(endpoint = "/v1/chat/completions", responseTimeMs = ms, statusCode = 200))
                                    call.respond(
                                        OaiChatResponse(
                                            id = "chatcmpl-${System.currentTimeMillis()}",
                                            created = System.currentTimeMillis() / 1000,
                                            model = modelId,
                                            choices = listOf(
                                                OaiChoice(
                                                    index = 0,
                                                    message = OaiMessage(
                                                        role = "assistant",
                                                        content = parsedToolCalls.content,
                                                        toolCalls = parsedToolCalls.toolCalls.takeIf { it.isNotEmpty() }
                                                    ),
                                                    finishReason = if (parsedToolCalls.toolCalls.isNotEmpty()) "tool_calls" else "stop"
                                                )
                                            ),
                                            usage = engine.getLastGenerationUsage().toOaiUsage()
                                        )
                                    )
                                }
                            }
                        }

                        // ── Legacy routes (kept for backward compat) ─────────
                        post("/chat") {
                            if (!call.requireApiToken()) return@post
                            val start = System.currentTimeMillis()
                            val req = call.receive<ChatRequest>()
                            if (!engine.isReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Engine not ready", 503)
                                )
                                return@post
                            }
                            val tokens = engine.generateStatelessText(req.message).toList()
                            val response = tokens.joinToString("")
                            val ms = System.currentTimeMillis() - start
                            onRequest(RequestLogEntry(endpoint = "/chat", responseTimeMs = ms, statusCode = 200))
                            call.respond(
                                ChatResponse(response = response, tokens = tokens.size, ms = ms)
                            )
                        }

                        post("/vision") {
                            if (!call.requireApiToken()) return@post
                            call.respond(
                                HttpStatusCode.NotImplemented,
                                ErrorResponse(
                                    error = "Vision is disabled for the current text-only LiteRT-LM engine configuration",
                                    code = 501
                                )
                            )
                        }

                        post("/reset") {
                            if (!call.requireApiToken()) return@post
                            engine.clearHistory()
                            onRequest(RequestLogEntry(endpoint = "/reset", responseTimeMs = 0, statusCode = 200))
                            call.respond(mapOf("status" to "conversation cleared"))
                        }
                    }
                }
                server!!.start(wait = false)
                port = tryPort
                return tryPort
            } catch (e: Exception) {
                if (tryPort == 8082) throw e
            }
        }
        throw IllegalStateException("Could not bind to any port (8080-8082)")
    }

    private fun buildPrompt(req: OaiChatRequest): String {
        val toolInstructions = req.tools
            ?.filter { it.type == "function" }
            ?.takeIf { it.isNotEmpty() }
            ?.let(::buildToolInstructions)
            .orEmpty()
        val history = req.messages.mapNotNull { message ->
            val content = message.content.toPromptText().trim()
            when {
                content.isNotEmpty() -> "${message.role}: $content"
                message.role == "assistant" -> "assistant:"
                message.role == "tool" -> "tool: ${message.name ?: message.toolCallId ?: "result"}"
                else -> null
            }
        }.joinToString("\n")
        val chat = if (history.isEmpty()) "assistant:" else "$history\nassistant:"
        return listOf(toolInstructions, chat)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun buildToolInstructions(tools: List<OaiTool>): String {
        val definitions = tools.joinToString("\n") { tool ->
            val function = tool.function
            "- ${function.name}: ${function.description.orEmpty()}\n  parameters: ${function.parameters ?: JsonObject(emptyMap())}"
        }
        return "Available tools:\n$definitions\n" +
            "If a tool is required, respond only with Gemma tool-call syntax: " +
            "<|tool_call>call:function_name{argument:<|\"|>value<|\"|>}<tool_call|>. " +
            "Do not wrap tool calls in markdown or prose."
    }

    private fun JsonElement?.toPromptText(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> contentOrNull ?: toString()
        is JsonArray -> mapNotNull { it.contentPartToText().takeIf(String::isNotBlank) }.joinToString("\n")
        is JsonObject -> contentPartToText()
    }

    private fun JsonElement.contentPartToText(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        is JsonArray -> mapNotNull { it.contentPartToText().takeIf(String::isNotBlank) }.joinToString("\n")
        is JsonObject -> {
            val type = this["type"]?.toPromptText()
            val text = this["text"]?.toPromptText()
                ?: this["input_text"]?.toPromptText()
                ?: this["content"]?.toPromptText()
            when {
                !text.isNullOrBlank() -> text
                type == "image_url" || type == "input_image" -> "[image omitted]"
                else -> entries.joinToString(", ") { (key, value) -> "$key=${value.toPromptText()}" }
            }
        }
    }

    private fun parseGemmaToolCalls(rawContent: String): ParsedToolCalls {
        val matches = GEMMA_TOOL_CALL_REGEX.findAll(rawContent).toList()
        if (matches.isEmpty()) return ParsedToolCalls(rawContent, emptyList())

        val calls = matches.mapIndexedNotNull { index, match ->
            parseGemmaToolCall(match.groupValues[1], index)
        }
        if (calls.isEmpty()) return ParsedToolCalls(rawContent, emptyList())

        val remainingContent = GEMMA_TOOL_CALL_REGEX.replace(rawContent, "").trim()
        DiagnosticsLogger.event("HttpApiServer", "parsed Gemma tool calls count=${calls.size}")
        return ParsedToolCalls(content = remainingContent, toolCalls = calls)
    }

    private fun parseGemmaToolCall(body: String, index: Int): OaiToolCall? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("call:")) return null
        val argsStart = trimmed.indexOf('{')
        val argsEnd = trimmed.lastIndexOf('}')
        val nameEnd = if (argsStart >= 0) argsStart else trimmed.length
        val name = trimmed.substring("call:".length, nameEnd).trim()
        if (name.isEmpty()) return null

        val arguments = if (argsStart >= 0 && argsEnd > argsStart) {
            parseGemmaToolArguments(trimmed.substring(argsStart + 1, argsEnd)).toString()
        } else {
            "{}"
        }
        return OaiToolCall(
            id = "call_${System.currentTimeMillis()}_$index",
            function = OaiFunctionCall(name = name, arguments = arguments)
        )
    }

    private fun parseGemmaToolArguments(rawArgs: String): JsonObject = buildJsonObject {
        splitGemmaArguments(rawArgs).forEach { pair ->
            val separator = pair.indexOf(':')
            if (separator <= 0) return@forEach
            val key = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (key.isNotEmpty()) put(key, parseGemmaToolValue(value))
        }
    }

    private fun splitGemmaArguments(rawArgs: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var inGemmaString = false
        var i = 0
        while (i < rawArgs.length) {
            if (rawArgs.startsWith(GEMMA_STRING_DELIMITER, i)) {
                inGemmaString = !inGemmaString
                i += GEMMA_STRING_DELIMITER.length
                continue
            }
            if (!inGemmaString && rawArgs[i] == ',') {
                parts += rawArgs.substring(start, i)
                start = i + 1
            }
            i += 1
        }
        parts += rawArgs.substring(start)
        return parts
    }

    private fun parseGemmaToolValue(value: String): JsonElement {
        if (value.startsWith(GEMMA_STRING_DELIMITER) && value.endsWith(GEMMA_STRING_DELIMITER)) {
            return JsonPrimitive(
                value.removePrefix(GEMMA_STRING_DELIMITER)
                    .removeSuffix(GEMMA_STRING_DELIMITER)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
            )
        }
        return when {
            value.equals("true", ignoreCase = true) -> JsonPrimitive(true)
            value.equals("false", ignoreCase = true) -> JsonPrimitive(false)
            value.equals("null", ignoreCase = true) -> JsonNull
            value.toLongOrNull() != null -> JsonPrimitive(value.toLong())
            value.toDoubleOrNull() != null -> JsonPrimitive(value.toDouble())
            else -> JsonPrimitive(value)
        }
    }


    private fun GenerationUsage?.toOaiUsage(): OaiUsage? =
        this?.let {
            OaiUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )
        }

    private suspend fun ApplicationCall.requireApiToken(): Boolean {
        if (request.authorization() == "Bearer $apiToken") {
            return true
        }
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(error = "Missing or invalid bearer token", code = 401)
        )
        return false
    }

    fun stop() {
        DiagnosticsLogger.event("HttpApiServer", "Stopping server on port=$port")
        server?.stop(1000, 5000)
        server = null
    }
}
