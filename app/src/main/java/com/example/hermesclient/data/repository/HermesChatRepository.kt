package com.example.hermesclient.data.repository

import com.example.hermesclient.core.network.ConnectionRuntime
import com.example.hermesclient.core.network.EndpointResolver
import com.example.hermesclient.core.network.HermesErrorMapper
import com.example.hermesclient.data.remote.api.HermesApi
import com.example.hermesclient.data.remote.dto.ApprovalResponseRequestDto
import com.example.hermesclient.data.remote.dto.RunRequestDto
import com.example.hermesclient.data.remote.dto.RunMessageDto
import com.example.hermesclient.data.remote.sse.HermesEventParser
import com.example.hermesclient.data.remote.sse.HermesSseDataSource
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.ChatEvent
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.MessageRole
import com.example.hermesclient.domain.repository.ChatRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class HermesChatRepository @Inject constructor(
    private val api: HermesApi,
    private val sseDataSource: HermesSseDataSource,
    private val eventParser: HermesEventParser,
    private val connectionRuntime: ConnectionRuntime,
    private val endpointResolver: EndpointResolver,
    private val errorMapper: HermesErrorMapper,
) : ChatRepository {
    override fun streamMessage(
        sessionId: String,
        message: String,
        conversationHistory: List<ChatMessage>,
    ): Flow<ChatEvent> = flow {
        var activeRunId: String? = null
        var terminalEventReceived = false
        try {
            val config = connectionRuntime.config()
            if (config == null) {
                emit(ChatEvent.Failure(HermesError.MissingApiKey))
                return@flow
            }
            val run = api.createRun(
                endpointResolver.resolve(config.baseUrl, "v1/runs"),
                RunRequestDto(
                    input = message,
                    sessionId = sessionId,
                    conversationHistory = conversationHistory.mapNotNull { it.toRunMessage() },
                ),
            )
            activeRunId = run.runId
            emit(ChatEvent.RunStarted(run.runId))
            val eventEndpoint = endpointResolver.resolveSegments(
                config.baseUrl,
                "v1",
                "runs",
                run.runId,
                "events",
            )
            sseDataSource.stream(eventEndpoint).collect { rawEvent ->
                eventParser.parse(rawEvent.name, rawEvent.data)?.let { event ->
                    if (event is ChatEvent.Completed ||
                        event is ChatEvent.Failure ||
                        event is ChatEvent.Stopped
                    ) {
                        terminalEventReceived = true
                    }
                    emit(event)
                }
            }
        } finally {
            val orphanedRunId = activeRunId
            if (!terminalEventReceived && orphanedRunId != null) {
                withContext(NonCancellable) {
                    stopRun(orphanedRunId)
                }
            }
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(ChatEvent.Failure(errorMapper.map(error)))
    }

    override suspend fun respondToApproval(runId: String, choice: ApprovalChoice): Result<Unit> =
        request {
            val config = connectionRuntime.config() ?: throw HermesError.MissingApiKey
            api.respondToApproval(
                endpointResolver.resolveSegments(
                    config.baseUrl,
                    "v1",
                    "runs",
                    runId,
                    "approval",
                ),
                ApprovalResponseRequestDto(choice.wireValue),
            )
        }

    override suspend fun stopRun(runId: String): Result<Unit> = request {
        val config = connectionRuntime.config() ?: throw HermesError.MissingApiKey
        api.stopRun(
            endpointResolver.resolveSegments(config.baseUrl, "v1", "runs", runId, "stop"),
        )
    }

    private suspend fun <T> request(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(errorMapper.map(error))
    }

    private fun ChatMessage.toRunMessage(): RunMessageDto? {
        val role = when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
            MessageRole.TOOL, MessageRole.UNKNOWN -> return null
        }
        return RunMessageDto(role = role, content = content)
    }
}
