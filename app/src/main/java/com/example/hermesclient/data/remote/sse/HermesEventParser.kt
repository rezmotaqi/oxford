package com.example.hermesclient.data.remote.sse

import com.example.hermesclient.data.remote.dto.MessageDto
import com.example.hermesclient.data.remote.mapper.toDomain
import com.example.hermesclient.domain.model.ChatEvent
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.model.MessageRole
import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

interface HermesEventParser {
    fun parse(eventName: String?, data: String): ChatEvent?
}

class DefaultHermesEventParser @Inject constructor(
    private val json: Json,
) : HermesEventParser {
    override fun parse(eventName: String?, data: String): ChatEvent? = runCatching {
        val payload = json.decodeFromString<JsonObject>(data)
        when (payload.string("event") ?: eventName) {
            "assistant.delta", "message.delta" -> ChatEvent.AssistantDelta(
                messageId = payload.string("message_id"),
                text = payload.string("delta").orEmpty(),
            )

            "tool.started" -> ChatEvent.ToolStarted(
                id = payload.eventId(),
                name = payload.string("tool_name") ?: payload.string("tool") ?: "tool",
                preview = payload.string("preview"),
            )

            "tool.completed", "tool.failed" -> ChatEvent.ToolCompleted(
                id = payload.eventId(),
                name = payload.string("tool_name") ?: payload.string("tool") ?: "tool",
                preview = payload.string("preview"),
                succeeded = (payload.string("event") ?: eventName) == "tool.completed" &&
                    payload.boolean("error") != true,
            )

            "approval.request" -> ChatEvent.ApprovalRequested(
                runId = payload.string("run_id") ?: return@runCatching null,
                command = payload.string("command").orEmpty(),
                description = payload.string("description") ?: "Hermes needs permission to continue.",
                choices = payload.approvalChoices(),
            )

            "approval.responded" -> ChatEvent.ApprovalResponded(
                runId = payload.string("run_id") ?: return@runCatching null,
                choice = ApprovalChoice.fromWireValue(payload.string("choice").orEmpty())
                    ?: return@runCatching null,
            )

            "run.completed" -> ChatEvent.Completed(
                payload.messages().ifEmpty {
                    payload.string("output")?.takeIf(String::isNotBlank)?.let { output ->
                        listOf(
                            ChatMessage(
                                id = null,
                                role = MessageRole.ASSISTANT,
                                content = output,
                                createdAt = null,
                            ),
                        )
                    }.orEmpty()
                },
            )
            "run.cancelled" -> ChatEvent.Stopped(
                runId = payload.string("run_id") ?: return@runCatching null,
            )
            "error", "run.failed" -> ChatEvent.Failure(
                HermesError.StreamFailure(
                    payload.string("message") ?: payload.string("error")
                    ?: "Hermes stopped the request.",
                ),
            )

            else -> null
        }
    }.getOrNull()

    private fun JsonObject.eventId(): String = string("message_id")
        ?: listOfNotNull(string("run_id"), numberOrString("seq") ?: numberOrString("timestamp"))
            .joinToString(":")
            .ifEmpty { "tool" }

    private fun JsonObject.approvalChoices(): Set<ApprovalChoice> =
        (this["choices"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ApprovalChoice::fromWireValue) }
            ?.toSet()
            .orEmpty()
            .ifEmpty { ApprovalChoice.entries.toSet() }

    private fun JsonObject.messages() = (this["messages"] as? JsonArray)
        ?.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<MessageDto>(element).toDomain() }.getOrNull()
        }
        .orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.numberOrString(key: String): String? = when (val value: JsonElement? = this[key]) {
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
