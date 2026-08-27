package com.example.hermesclient.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.example.hermesclient.MainDispatcherRule
import com.example.hermesclient.domain.model.ChatEvent
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ChatSession
import com.example.hermesclient.domain.model.MessageRole
import com.example.hermesclient.domain.repository.ChatRepository
import com.example.hermesclient.domain.repository.SessionRepository
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var sessionRepository: FakeSessionRepository
    private lateinit var chatRepository: FakeChatRepository

    @Before
    fun setUp() {
        sessionRepository = FakeSessionRepository()
        chatRepository = FakeChatRepository()
    }

    @Test
    fun `assistant deltas update one message and completion ends streaming`() =
        runTest(mainDispatcherRule.dispatcher) {
            chatRepository.events = flowOf(
                ChatEvent.AssistantDelta("assistant-1", "Hel"),
                ChatEvent.AssistantDelta("assistant-1", "lo"),
                ChatEvent.Completed(
                    listOf(message("assistant-1", MessageRole.ASSISTANT, "Hello")),
                ),
            )
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateInput("Hello Hermes")
            viewModel.sendMessage()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val assistantMessages = state.items.filterIsInstance<ChatItem.Message>()
                .filter { it.message.role == MessageRole.ASSISTANT }
            assertEquals(1, assistantMessages.size)
            assertEquals("Hello", assistantMessages.single().message.content)
            assertEquals(false, assistantMessages.single().isStreaming)
            assertEquals(StreamingState.Idle, state.streamingState)
        }

    @Test
    fun `tool lifecycle remains coherent around assistant streaming`() =
        runTest(mainDispatcherRule.dispatcher) {
            chatRepository.events = flowOf(
                ChatEvent.ToolStarted("tool-1", "read_file", "Reading file"),
                ChatEvent.ToolCompleted("tool-1", "read_file", "Read file", succeeded = true),
                ChatEvent.AssistantDelta("assistant-1", "Done"),
                ChatEvent.Completed(emptyList()),
            )
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateInput("Read it")
            viewModel.sendMessage()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val tool = state.items.filterIsInstance<ChatItem.ToolActivity>().single()
            assertEquals("read_file", tool.toolName)
            assertEquals(ToolState.Completed(succeeded = true), tool.state)
            assertEquals("Read file", tool.preview)
            assertTrue(
                state.items.filterIsInstance<ChatItem.Message>()
                    .any { it.message.role == MessageRole.ASSISTANT && it.message.content == "Done" },
            )
            assertEquals(StreamingState.Idle, state.streamingState)
        }

    @Test
    fun `send is ignored while a turn is already streaming`() =
        runTest(mainDispatcherRule.dispatcher) {
            val finishStream = CompletableDeferred<Unit>()
            chatRepository.events = kotlinx.coroutines.flow.flow {
                emit(ChatEvent.AssistantDelta("assistant-1", "Working"))
                finishStream.await()
                emit(ChatEvent.Completed(emptyList()))
            }
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateInput("First")
            viewModel.sendMessage()
            viewModel.updateInput("Second")
            viewModel.sendMessage()

            assertEquals(1, chatRepository.sendCount)
            assertEquals(StreamingState.Streaming, viewModel.uiState.value.streamingState)

            finishStream.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `approval response updates focused approval item`() =
        runTest(mainDispatcherRule.dispatcher) {
            chatRepository.events = kotlinx.coroutines.flow.flow {
                emit(ChatEvent.RunStarted("run-1"))
                emit(
                    ChatEvent.ApprovalRequested(
                        runId = "run-1",
                        command = "delete temp.txt",
                        description = "Delete a temporary file",
                        choices = setOf(ApprovalChoice.ONCE, ApprovalChoice.DENY),
                    ),
                )
                chatRepository.approvalResponse.await()
                emit(ChatEvent.ApprovalResponded("run-1", ApprovalChoice.ONCE))
                emit(ChatEvent.Completed(emptyList()))
            }
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateInput("Clean up")
            viewModel.sendMessage()
            runCurrent()

            val pending = viewModel.uiState.value.items.filterIsInstance<ChatItem.Approval>().single()
            assertEquals(ApprovalState.Pending, pending.state)
            viewModel.respondToApproval(pending.key, ApprovalChoice.ONCE)
            advanceUntilIdle()

            val resolved = viewModel.uiState.value.items.filterIsInstance<ChatItem.Approval>().single()
            assertEquals(ApprovalState.Resolved(ApprovalChoice.ONCE), resolved.state)
            assertEquals(listOf("run-1" to ApprovalChoice.ONCE), chatRepository.approvalCalls)
        }

    @Test
    fun `stop request terminates active run and preserves partial response`() =
        runTest(mainDispatcherRule.dispatcher) {
            chatRepository.events = kotlinx.coroutines.flow.flow {
                emit(ChatEvent.RunStarted("run-1"))
                emit(ChatEvent.AssistantDelta("assistant-1", "Partial"))
                chatRepository.stopRequested.await()
                emit(ChatEvent.Stopped("run-1"))
            }
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateInput("Long task")
            viewModel.sendMessage()
            runCurrent()
            assertTrue(viewModel.uiState.value.canStop)

            viewModel.stopRun()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(StreamingState.Idle, state.streamingState)
            assertEquals(null, state.activeRunId)
            assertEquals("Partial", state.items.filterIsInstance<ChatItem.Message>().last().message.content)
            assertEquals(listOf("run-1"), chatRepository.stopCalls)
        }

    private fun createViewModel() = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ChatViewModel.SESSION_ID_KEY to SESSION_ID)),
        sessionRepository = sessionRepository,
        chatRepository = chatRepository,
    )

    private fun message(id: String, role: MessageRole, content: String) = ChatMessage(
        id = id,
        role = role,
        content = content,
        createdAt = Instant.EPOCH,
    )

    private class FakeSessionRepository : SessionRepository {
        override suspend fun getSessions() = Result.success(emptyList<ChatSession>())

        override suspend fun createSession() = Result.failure<ChatSession>(UnsupportedOperationException())

        override suspend fun getSession(sessionId: String) = Result.success(
            ChatSession(sessionId, "Test", Instant.EPOCH, Instant.EPOCH),
        )

        override suspend fun getMessages(sessionId: String) = Result.success(emptyList<ChatMessage>())
    }

    private class FakeChatRepository : ChatRepository {
        var events: Flow<ChatEvent> = flowOf(ChatEvent.Completed(emptyList()))
        var sendCount = 0
        val approvalResponse = CompletableDeferred<Unit>()
        val stopRequested = CompletableDeferred<Unit>()
        val approvalCalls = mutableListOf<Pair<String, ApprovalChoice>>()
        val stopCalls = mutableListOf<String>()

        override fun streamMessage(
            sessionId: String,
            message: String,
            conversationHistory: List<ChatMessage>,
        ): Flow<ChatEvent> {
            sendCount += 1
            return events
        }

        override suspend fun respondToApproval(runId: String, choice: ApprovalChoice): Result<Unit> {
            approvalCalls += runId to choice
            approvalResponse.complete(Unit)
            return Result.success(Unit)
        }

        override suspend fun stopRun(runId: String): Result<Unit> {
            stopCalls += runId
            stopRequested.complete(Unit)
            return Result.success(Unit)
        }
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
