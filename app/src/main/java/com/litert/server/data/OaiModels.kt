package com.litert.server.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The small OpenAI-compatible surface implemented by the local server. */
@Serializable
data class OaiChatRequest(
    val model: String,
    val messages: List<OaiRequestMessage>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
    val tools: List<OaiTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null
)

@Serializable
data class OaiRequestMessage(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiToolCall>? = null
)

@Serializable
data class OaiChatResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OaiChoice>,
    val usage: OaiUsage
)

@Serializable
data class OaiChoice(
    val index: Int,
    val message: OaiMessage,
    @SerialName("finish_reason") val finishReason: String
)

@Serializable
data class OaiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiToolCall>? = null
)

@Serializable
data class OaiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

@Serializable
data class OaiTool(
    val type: String = "function",
    val function: OaiFunctionDefinition
)

@Serializable
data class OaiFunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null
)

@Serializable
data class OaiToolCall(
    val id: String = "",
    val type: String = "function",
    val function: OaiFunctionCall
)

@Serializable
data class OaiFunctionCall(
    val name: String,
    val arguments: String = "{}"
)

@Serializable
data class OaiStreamChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<OaiStreamChoice>,
    val usage: OaiUsage? = null
)

@Serializable
data class OaiStreamChoice(
    val index: Int,
    val delta: OaiDelta,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OaiDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiStreamToolCall>? = null
)

@Serializable
data class OaiStreamToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: OaiStreamFunctionCall? = null
)

@Serializable
data class OaiStreamFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class OaiModelsResponse(
    val `object`: String = "list",
    val data: List<OaiModelEntry>
)

@Serializable
data class OaiModelEntry(
    val id: String,
    val `object`: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "local"
)

@Serializable
data class OaiHealthResponse(
    val status: String,
    val model: String,
    val ready: Boolean,
    @SerialName("backend_error") val backendError: String? = null,
    val gpu: Boolean
)

@Serializable
data class OaiErrorResponse(
    val error: OaiError
)

@Serializable
data class OaiError(
    val message: String,
    val type: String = "invalid_request_error",
    val param: String? = null,
    val code: String? = null
)
