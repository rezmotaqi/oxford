package com.example.hermesclient.data.remote.mapper

import com.example.hermesclient.data.remote.dto.CapabilitiesDto
import com.example.hermesclient.data.remote.dto.MessageDto
import com.example.hermesclient.data.remote.dto.SessionDto
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ChatSession
import com.example.hermesclient.domain.model.HermesCapabilities
import com.example.hermesclient.domain.model.MessageRole
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun CapabilitiesDto.toDomain(): HermesCapabilities = HermesCapabilities(
    sessionsSupported = features.sessionResources,
    sessionStreamingSupported = features.sessionChatStreaming,
    runsSupported = features.runSubmission &&
        features.runStatus &&
        features.runEventsSse &&
        features.runStop,
    approvalsSupported = features.runApprovalResponse && features.approvalEvents,
)

fun SessionDto.toDomain(): ChatSession = ChatSession(
    id = id,
    title = title,
    createdAt = startedAt.toInstantOrNull(),
    updatedAt = lastActive.toInstantOrNull(),
)

fun MessageDto.toDomain(): ChatMessage = ChatMessage(
    id = when (id) {
        is JsonPrimitive -> id.contentOrNull
        null -> null
        else -> id.toString()
    },
    role = when (role.lowercase()) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "system" -> MessageRole.SYSTEM
        "tool" -> MessageRole.TOOL
        else -> MessageRole.UNKNOWN
    },
    content = content.visibleText(),
    createdAt = timestamp.toInstantOrNull(),
    toolName = toolName,
)

private fun Double?.toInstantOrNull(): Instant? = this
    ?.takeIf { it.isFinite() && it >= 0 }
    ?.let { seconds ->
        val wholeSeconds = seconds.toLong()
        val nanos = ((seconds - wholeSeconds) * 1_000_000_000).toLong()
        runCatching { Instant.ofEpochSecond(wholeSeconds, nanos) }.getOrNull()
    }

private fun JsonElement.visibleText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString(separator = "") { it.visibleText() }
    is JsonObject -> {
        val type = (this["type"] as? JsonPrimitive)?.contentOrNull
        when (type) {
            "text", "input_text", "output_text" -> this["text"]?.visibleText().orEmpty()
            else -> this["text"]?.visibleText()
                ?: this["content"]?.visibleText()
                ?: ""
        }
    }
}
