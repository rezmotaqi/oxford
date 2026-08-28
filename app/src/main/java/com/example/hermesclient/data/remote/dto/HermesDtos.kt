package com.example.hermesclient.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class HealthDto(
    val status: String,
    val platform: String? = null,
    val version: String? = null,
)

@Serializable
data class CapabilitiesDto(
    val features: CapabilityFeaturesDto = CapabilityFeaturesDto(),
)

@Serializable
data class CapabilityFeaturesDto(
    @SerialName("session_resources") val sessionResources: Boolean = false,
    @SerialName("session_chat_streaming") val sessionChatStreaming: Boolean = false,
    @SerialName("run_submission") val runSubmission: Boolean = false,
    @SerialName("run_status") val runStatus: Boolean = false,
    @SerialName("run_events_sse") val runEventsSse: Boolean = false,
    @SerialName("run_stop") val runStop: Boolean = false,
    @SerialName("run_approval_response") val runApprovalResponse: Boolean = false,
    @SerialName("approval_events") val approvalEvents: Boolean = false,
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
)

@Serializable
data class SessionListDto(
    val data: List<SessionDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SessionEnvelopeDto(
    val session: SessionDto,
)

@Serializable
class CreateSessionRequestDto

@Serializable
data class MessageDto(
    val id: JsonElement? = null,
    val role: String,
    val content: JsonElement,
    val timestamp: Double? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

@Serializable
data class MessageListDto(
    @SerialName("session_id") val sessionId: String,
    val data: List<MessageDto> = emptyList(),
)

@Serializable
data class ChatRequestDto(
    val input: String,
)

@Serializable
data class RunRequestDto(
    val input: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("conversation_history") val conversationHistory: List<RunMessageDto> = emptyList(),
)

@Serializable
data class RunMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class RunCreatedDto(
    @SerialName("run_id") val runId: String,
    val status: String,
)

@Serializable
data class ApprovalResponseRequestDto(
    val choice: String,
)

@Serializable
data class ApprovalResponseDto(
    @SerialName("run_id") val runId: String,
    val choice: String,
    val resolved: Int,
)

@Serializable
data class SteerRunRequestDto(
    val input: String,
)

@Serializable
data class SteerRunDto(
    @SerialName("run_id") val runId: String,
    val accepted: Boolean,
)

@Serializable
data class StopRunDto(
    @SerialName("run_id") val runId: String,
    val status: String,
)
