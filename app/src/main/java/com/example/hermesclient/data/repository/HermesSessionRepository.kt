package com.example.hermesclient.data.repository

import com.example.hermesclient.core.network.ConnectionRuntime
import com.example.hermesclient.core.network.EndpointResolver
import com.example.hermesclient.core.network.HermesErrorMapper
import com.example.hermesclient.data.remote.api.HermesApi
import com.example.hermesclient.data.remote.dto.CreateSessionRequestDto
import com.example.hermesclient.data.remote.mapper.toDomain
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ChatSession
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.repository.SessionRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class HermesSessionRepository @Inject constructor(
    private val api: HermesApi,
    private val connectionRuntime: ConnectionRuntime,
    private val endpointResolver: EndpointResolver,
    private val errorMapper: HermesErrorMapper,
) : SessionRepository {
    override suspend fun getSessions(): Result<List<ChatSession>> = request {
        val config = connectionRuntime.config() ?: throw HermesError.MissingApiKey
        val endpoint = endpointResolver.resolve(config.baseUrl, "api/sessions")
        val sessions = buildList {
            var offset = 0
            do {
                val page = api.sessions(endpoint, limit = PAGE_SIZE, offset = offset)
                addAll(page.data.map { it.toDomain() })
                offset += page.data.size
            } while (page.hasMore && page.data.isNotEmpty())
        }
        sessions
    }

    override suspend fun createSession(): Result<ChatSession> = request {
        val config = connectionRuntime.config() ?: throw HermesError.MissingApiKey
        api.createSession(
            endpointResolver.resolve(config.baseUrl, "api/sessions"),
            CreateSessionRequestDto(),
        ).session.toDomain()
    }

    override suspend fun getSession(sessionId: String): Result<ChatSession> = request {
        val config = connectionRuntime.config() ?: throw HermesError.MissingApiKey
        api.session(sessionEndpoint(config.baseUrl, sessionId)).session.toDomain()
    }

    override suspend fun getMessages(sessionId: String): Result<List<ChatMessage>> = request {
        val config = connectionRuntime.config() ?: throw HermesError.MissingApiKey
        api.messages(
            endpointResolver.resolveSegments(
                config.baseUrl,
                "api",
                "sessions",
                sessionId,
                "messages",
            ),
        ).data.map { it.toDomain() }
    }

    private fun sessionEndpoint(baseUrl: String, sessionId: String): String =
        endpointResolver.resolveSegments(baseUrl, "api", "sessions", sessionId)

    private suspend fun <T> request(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(errorMapper.map(error))
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
