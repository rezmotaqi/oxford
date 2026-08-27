package com.example.hermesclient.domain.model

import java.time.Instant

data class HermesConnectionConfig(
    val baseUrl: String,
    val apiKey: String,
)

data class HermesCapabilities(
    val sessionsSupported: Boolean,
    val sessionStreamingSupported: Boolean,
    val runsSupported: Boolean,
    val approvalsSupported: Boolean,
)

data class ChatSession(
    val id: String,
    val title: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class ChatMessage(
    val id: String?,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant?,
    val toolName: String? = null,
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    UNKNOWN,
}

sealed interface ChatEvent {
    data class RunStarted(
        val runId: String,
    ) : ChatEvent

    data class AssistantDelta(
        val messageId: String?,
        val text: String,
    ) : ChatEvent

    data class ToolStarted(
        val id: String,
        val name: String,
        val preview: String?,
    ) : ChatEvent

    data class ToolCompleted(
        val id: String,
        val name: String,
        val preview: String?,
        val succeeded: Boolean,
    ) : ChatEvent

    data class ApprovalRequested(
        val runId: String,
        val command: String,
        val description: String,
        val choices: Set<ApprovalChoice>,
    ) : ChatEvent

    data class ApprovalResponded(
        val runId: String,
        val choice: ApprovalChoice,
    ) : ChatEvent

    data class Stopped(
        val runId: String,
    ) : ChatEvent

    data class Completed(
        val messages: List<ChatMessage>,
    ) : ChatEvent

    data class Failure(
        val error: HermesError,
    ) : ChatEvent
}

enum class ApprovalChoice(val wireValue: String) {
    ONCE("once"),
    SESSION("session"),
    ALWAYS("always"),
    DENY("deny");

    companion object {
        fun fromWireValue(value: String): ApprovalChoice? = entries.firstOrNull {
            it.wireValue == value.lowercase()
        }
    }
}

sealed class HermesError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    data object Unauthorized : HermesError()
    data object Unreachable : HermesError()
    data object Timeout : HermesError()
    data object UnsupportedServer : HermesError()
    data object SessionsUnsupported : HermesError()
    data object SessionStreamingUnsupported : HermesError()
    data object InvalidUrl : HermesError()
    data object MissingApiKey : HermesError()

    data class ServerError(val statusCode: Int) : HermesError()

    data class StreamFailure(val safeMessage: String) : HermesError(safeMessage)

    class Unknown(cause: Throwable? = null) : HermesError(cause = cause)
}
