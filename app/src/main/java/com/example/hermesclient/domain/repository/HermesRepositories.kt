package com.example.hermesclient.domain.repository

import com.example.hermesclient.domain.model.ChatEvent
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ChatSession
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.HermesCapabilities
import com.example.hermesclient.domain.model.HermesConnectionConfig
import kotlinx.coroutines.flow.Flow

interface AgentBackend {
    suspend fun testConnection(config: HermesConnectionConfig): Result<HermesCapabilities>
}

interface ConnectionRepository {
    suspend fun getSavedConfig(): HermesConnectionConfig?

    suspend fun testAndSave(
        baseUrl: String,
        apiKey: String,
    ): Result<HermesCapabilities>
}

interface SessionRepository {
    suspend fun getSessions(): Result<List<ChatSession>>

    suspend fun createSession(): Result<ChatSession>

    suspend fun getSession(sessionId: String): Result<ChatSession>

    suspend fun getMessages(sessionId: String): Result<List<ChatMessage>>
}

interface ChatRepository {
    fun streamMessage(
        sessionId: String,
        message: String,
        conversationHistory: List<ChatMessage> = emptyList(),
    ): Flow<ChatEvent>

    suspend fun respondToApproval(runId: String, choice: ApprovalChoice): Result<Unit>

    suspend fun steerRun(runId: String, input: String): Result<Unit>

    suspend fun stopRun(runId: String): Result<Unit>
}
