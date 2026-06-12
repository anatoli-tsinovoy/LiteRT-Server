package com.litert.server.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// OpenAI-compatible request/response models

@Serializable
data class OaiChatRequest(
    val model: String = "local-litertlm",
    val messages: List<OaiRequestMessage>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null
)

@Serializable
data class OaiRequestMessage(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class OaiMessage(
    val role: String,
    val content: String
)

@Serializable
data class OaiChatResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OaiChoice>,
    val usage: OaiUsage? = null
)

@Serializable
data class OaiChoice(
    val index: Int,
    val message: OaiMessage,
    @SerialName("finish_reason") val finishReason: String
)

@Serializable
data class OaiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
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
    val content: String? = null
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
    val created: Long = 1_700_000_000L,
    @SerialName("owned_by") val ownedBy: String = "google"
)
