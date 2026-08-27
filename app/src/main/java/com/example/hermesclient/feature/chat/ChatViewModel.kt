package com.example.hermesclient.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hermesclient.domain.model.ChatEvent
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.model.MessageRole
import com.example.hermesclient.domain.repository.ChatRepository
import com.example.hermesclient.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {
    private val sessionId = savedStateHandle.get<String>(SESSION_ID_KEY).orEmpty()
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var activeTurn: ActiveTurn? = null
    private var approvalSequence = 0

    init {
        if (sessionId.isBlank()) {
            _uiState.update {
                it.copy(loadState = ChatLoadState.Failed("This conversation could not be opened."))
            }
        } else {
            loadChat()
        }
    }

    fun retryLoad() {
        if (sessionId.isNotBlank() && streamJob?.isActive != true) {
            loadChat()
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun sendMessage() {
        val snapshot = _uiState.value
        val text = snapshot.input.trim()
        if (!snapshot.canSend || text.isEmpty() || streamJob?.isActive == true) return

        val userKey = localKey("user")
        val userItem = ChatItem.Message(
            key = userKey,
            message = ChatMessage(
                id = null,
                role = MessageRole.USER,
                content = text,
                createdAt = null,
            ),
        )
        activeTurn = ActiveTurn(userKey = userKey, input = text)
        _uiState.update {
            it.copy(
                items = it.items + userItem,
                input = "",
                streamingState = StreamingState.Streaming,
                activeRunId = null,
                actionError = null,
            )
        }

        var terminalEventReceived = false
        streamJob = viewModelScope.launch {
            val conversationHistory = snapshot.items.mapNotNull { item ->
                (item as? ChatItem.Message)?.message
            }
            chatRepository.streamMessage(sessionId, text, conversationHistory)
                .catch { throwable ->
                    terminalEventReceived = true
                    failStream(throwable.toUserMessage())
                }
                .onCompletion { cause ->
                    if (cause == null && !terminalEventReceived) {
                        failStream("The response ended before Hermes completed it. You can send another message.")
                    }
                }
                .transformWhile { event ->
                    emit(event)
                    event !is ChatEvent.Completed &&
                        event !is ChatEvent.Failure &&
                        event !is ChatEvent.Stopped
                }
                .collect { event ->
                    when (event) {
                        is ChatEvent.RunStarted -> _uiState.update {
                            it.copy(activeRunId = event.runId)
                        }
                        is ChatEvent.AssistantDelta -> appendAssistantDelta(event)
                        is ChatEvent.ToolStarted -> addToolActivity(event)
                        is ChatEvent.ToolCompleted -> completeToolActivity(event)
                        is ChatEvent.ApprovalRequested -> addApproval(event)
                        is ChatEvent.ApprovalResponded -> resolveApproval(event)
                        is ChatEvent.Stopped -> {
                            terminalEventReceived = true
                            stopCompleted()
                        }
                        is ChatEvent.Completed -> {
                            terminalEventReceived = true
                            completeTurn(event.messages)
                        }
                        is ChatEvent.Failure -> {
                            terminalEventReceived = true
                            failStream(event.error.toUserMessage())
                        }
                    }
                }
        }
    }

    fun stopRun() {
        val runId = _uiState.value.activeRunId ?: return
        if (_uiState.value.streamingState != StreamingState.Streaming) return
        _uiState.update { it.copy(streamingState = StreamingState.Stopping, actionError = null) }
        viewModelScope.launch {
            chatRepository.stopRun(runId).onFailure { error ->
                _uiState.update {
                    it.copy(
                        streamingState = StreamingState.Streaming,
                        actionError = error.toUserMessage(),
                    )
                }
            }
        }
    }

    fun respondToApproval(key: String, choice: ApprovalChoice) {
        val approval = _uiState.value.items.firstOrNull { it.key == key } as? ChatItem.Approval
            ?: return
        if (approval.state !is ApprovalState.Pending && approval.state !is ApprovalState.Failed) return
        if (choice !in approval.choices) return
        _uiState.update { state ->
            state.copy(
                items = state.items.replaceItem(key) { item ->
                    (item as ChatItem.Approval).copy(state = ApprovalState.Responding(choice))
                },
                actionError = null,
            )
        }
        viewModelScope.launch {
            chatRepository.respondToApproval(approval.runId, choice).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            items = state.items.replaceItem(key) { item ->
                                (item as ChatItem.Approval).copy(
                                    state = ApprovalState.Resolved(choice),
                                )
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { state ->
                        state.copy(
                            items = state.items.replaceItem(key) { item ->
                                (item as ChatItem.Approval).copy(
                                    state = ApprovalState.Failed(error.toUserMessage()),
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    private fun loadChat() {
        _uiState.update {
            it.copy(
                loadState = ChatLoadState.Loading,
                streamingState = StreamingState.Idle,
                activeRunId = null,
                actionError = null,
            )
        }
        viewModelScope.launch {
            val (sessionResult, messagesResult) = coroutineScope {
                val session = async { safeResult { sessionRepository.getSession(sessionId) } }
                val messages = async { safeResult { sessionRepository.getMessages(sessionId) } }
                session.await() to messages.await()
            }

            sessionResult.fold(
                onSuccess = { session ->
                    messagesResult.fold(
                        onSuccess = { messages ->
                            _uiState.update {
                                it.copy(
                                    session = session,
                                    items = messages.mapIndexed(::historyItem),
                                    loadState = ChatLoadState.Ready,
                                )
                            }
                        },
                        onFailure = ::showLoadFailure,
                    )
                },
                onFailure = ::showLoadFailure,
            )
        }
    }

    private fun appendAssistantDelta(event: ChatEvent.AssistantDelta) {
        val turn = activeTurn ?: return
        _uiState.update { state ->
            val assistantIndex = state.items.indexOfLast {
                it is ChatItem.Message && it.key == turn.assistantKey
            }
            if (assistantIndex >= 0) {
                state.copy(
                    items = state.items.replaceAt(assistantIndex) { item ->
                        val current = item as ChatItem.Message
                        current.copy(
                            message = current.message.copy(
                                id = event.messageId ?: current.message.id,
                                content = current.message.content + event.text,
                            ),
                            isStreaming = true,
                        )
                    },
                )
            } else {
                state.copy(
                    items = state.items + ChatItem.Message(
                        key = turn.assistantKey,
                        message = ChatMessage(
                            id = event.messageId,
                            role = MessageRole.ASSISTANT,
                            content = event.text,
                            createdAt = null,
                        ),
                        isStreaming = true,
                    ),
                )
            }
        }
    }

    private fun addToolActivity(event: ChatEvent.ToolStarted) {
        if (activeTurn == null) return
        _uiState.update { state ->
            val key = toolKey(event.id)
            val existingIndex = state.items.indexOfLast { it.key == key }
            val activity = ChatItem.ToolActivity(
                key = key,
                toolName = event.name,
                state = ToolState.InProgress,
                preview = event.preview,
            )
            if (existingIndex >= 0) {
                state.copy(items = state.items.replaceAt(existingIndex) { activity })
            } else {
                state.copy(items = state.items + activity)
            }
        }
    }

    private fun completeToolActivity(event: ChatEvent.ToolCompleted) {
        if (activeTurn == null) return
        _uiState.update { state ->
            val exactIndex = state.items.indexOfLast { item ->
                item is ChatItem.ToolActivity &&
                    item.key == toolKey(event.id) &&
                    item.state == ToolState.InProgress
            }
            val matchingIndex = exactIndex.takeIf { it >= 0 } ?: state.items.indexOfLast { item ->
                item is ChatItem.ToolActivity &&
                    item.toolName == event.name &&
                    item.state == ToolState.InProgress
            }
            val completed = ToolState.Completed(event.succeeded)
            if (matchingIndex >= 0) {
                state.copy(
                    items = state.items.replaceAt(matchingIndex) { item ->
                        (item as ChatItem.ToolActivity).copy(
                            state = completed,
                            preview = event.preview ?: item.preview,
                        )
                    },
                )
            } else {
                val key = toolKey(event.id)
                val existingIndex = state.items.indexOfLast { it.key == key }
                val activity = ChatItem.ToolActivity(
                    key = key,
                    toolName = event.name,
                    state = completed,
                    preview = event.preview,
                )
                if (existingIndex >= 0) {
                    state.copy(items = state.items.replaceAt(existingIndex) { activity })
                } else {
                    state.copy(items = state.items + activity)
                }
            }
        }
    }

    private fun addApproval(event: ChatEvent.ApprovalRequested) {
        if (activeTurn == null) return
        val key = "approval-${event.runId}-${approvalSequence++}"
        _uiState.update { state ->
            state.copy(
                items = state.items + ChatItem.Approval(
                    key = key,
                    runId = event.runId,
                    command = event.command,
                    description = event.description,
                    choices = event.choices,
                ),
            )
        }
    }

    private fun resolveApproval(event: ChatEvent.ApprovalResponded) {
        _uiState.update { state ->
            val index = state.items.indexOfFirst { item ->
                item is ChatItem.Approval &&
                    item.runId == event.runId &&
                    item.state !is ApprovalState.Resolved
            }
            if (index < 0) state else state.copy(
                items = state.items.replaceAt(index) { item ->
                    (item as ChatItem.Approval).copy(
                        state = ApprovalState.Resolved(event.choice),
                    )
                },
            )
        }
    }

    private fun stopCompleted() {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    when {
                        item is ChatItem.Message && item.isStreaming -> item.copy(isStreaming = false)
                        item is ChatItem.Approval && item.state !is ApprovalState.Resolved ->
                            item.copy(state = ApprovalState.Resolved(ApprovalChoice.DENY))
                        else -> item
                    }
                },
                streamingState = StreamingState.Idle,
                activeRunId = null,
                actionError = null,
            )
        }
        activeTurn = null
    }

    private fun completeTurn(messages: List<ChatMessage>) {
        val turn = activeTurn ?: return
        _uiState.update { state ->
            val userIndex = state.items.indexOfLast { it.key == turn.userKey }
            if (userIndex < 0) {
                return@update state.copy(streamingState = StreamingState.Idle)
            }

            val authoritativeUser = messages.lastOrNull {
                it.role == MessageRole.USER && it.content == turn.input
            }
            val prefix = state.items.take(userIndex + 1).toMutableList()
            if (authoritativeUser != null) {
                prefix[userIndex] = (prefix[userIndex] as ChatItem.Message).copy(
                    message = authoritativeUser,
                )
            }

            val turnTranscript = messages.currentTurnTranscript(
                input = turn.input,
                existingItems = prefix.dropLast(1),
            )
            val authoritativeItems = turnTranscript
                .filter { it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL }
                .mapIndexed(::authoritativeItem)
            val streamedItems = state.items.drop(userIndex + 1).map { item ->
                if (item is ChatItem.Message) item.copy(isStreaming = false) else item
            }
            val retainedActivities = streamedItems.filter { item ->
                item is ChatItem.Approval ||
                    (item is ChatItem.ToolActivity && turnTranscript.none { it.role == MessageRole.TOOL })
            }

            state.copy(
                items = prefix + if (authoritativeItems.isEmpty()) {
                    streamedItems
                } else {
                    retainedActivities + authoritativeItems
                },
                streamingState = StreamingState.Idle,
                activeRunId = null,
                actionError = null,
            )
        }
        activeTurn = null
    }

    private fun failStream(message: String) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item is ChatItem.Message && item.isStreaming) {
                        item.copy(isStreaming = false)
                    } else {
                        item
                    }
                },
                streamingState = StreamingState.Failed(message),
                activeRunId = null,
            )
        }
        activeTurn = null
    }

    private fun showLoadFailure(throwable: Throwable) {
        _uiState.update {
            it.copy(loadState = ChatLoadState.Failed(throwable.toUserMessage()))
        }
    }

    private fun historyItem(index: Int, message: ChatMessage): ChatItem =
        when (message.role) {
            MessageRole.TOOL -> ChatItem.ToolActivity(
                key = message.id?.let { "history-tool-$index-$it" } ?: "history-tool-$index",
                toolName = message.toolName ?: "Tool",
                state = ToolState.Completed(succeeded = true),
                preview = message.content.takeIf(String::isNotBlank),
            )
            else -> ChatItem.Message(
                key = message.id?.let { "history-message-$index-$it" } ?: "history-message-$index",
                message = message,
            )
        }

    private fun authoritativeItem(index: Int, message: ChatMessage): ChatItem =
        when (message.role) {
            MessageRole.TOOL -> ChatItem.ToolActivity(
                key = message.id?.let { "completed-tool-$index-$it" }
                    ?: localKey("completed-tool-$index"),
                toolName = message.toolName ?: "Tool",
                state = ToolState.Completed(succeeded = true),
                preview = message.content.takeIf(String::isNotBlank),
            )
            else -> ChatItem.Message(
                key = message.id?.let { "completed-message-$index-$it" }
                    ?: localKey("completed-message-$index"),
                message = message,
            )
        }

    private data class ActiveTurn(
        val userKey: String,
        val input: String,
        val assistantKey: String = localKey("assistant"),
    )

    companion object {
        const val SESSION_ID_KEY = "sessionId"

        private fun localKey(prefix: String): String = "$prefix-${UUID.randomUUID()}"
        private fun toolKey(id: String): String = "tool-$id"
    }
}

private fun List<ChatItem>.replaceItem(
    key: String,
    transform: (ChatItem) -> ChatItem,
): List<ChatItem> {
    val index = indexOfFirst { it.key == key }
    return if (index < 0) this else replaceAt(index, transform)
}

private suspend inline fun <T> safeResult(
    crossinline block: suspend () -> Result<T>,
): Result<T> = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (throwable: Throwable) {
    Result.failure(throwable)
}

private fun List<ChatMessage>.currentTurnTranscript(
    input: String,
    existingItems: List<ChatItem>,
): List<ChatMessage> {
    val currentUserIndex = indexOfLast { it.role == MessageRole.USER && it.content == input }
    if (currentUserIndex >= 0) return drop(currentUserIndex + 1)

    val existingMessages = existingItems.mapNotNull { (it as? ChatItem.Message)?.message }
    return filterNot { candidate ->
        existingMessages.any { existing ->
            when {
                candidate.id != null && existing.id != null -> candidate.id == existing.id
                else -> candidate.role == existing.role &&
                    candidate.content == existing.content &&
                    candidate.toolName == existing.toolName
            }
        }
    }
}

private inline fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { currentIndex, item ->
        if (currentIndex == index) transform(item) else item
    }

private fun Throwable.toUserMessage(): String = when (this) {
    HermesError.Unauthorized -> "Your Hermes API key is no longer accepted. Check the connection settings."
    HermesError.Unreachable -> "Hermes could not be reached. Check the server connection and try again."
    HermesError.Timeout -> "Hermes took too long to respond. Please try again."
    HermesError.UnsupportedServer -> "This Hermes server does not support session streaming. Please update Hermes Agent."
    HermesError.SessionsUnsupported -> "This Hermes server does not support sessions. Please update Hermes Agent."
    HermesError.SessionStreamingUnsupported ->
        "This Hermes server does not support session streaming. Please update Hermes Agent."
    HermesError.InvalidUrl -> "The Hermes server address is invalid."
    HermesError.MissingApiKey -> "A Hermes API key is required."
    is HermesError.ServerError -> "Hermes returned a server error ($statusCode). Please try again."
    is HermesError.StreamFailure -> safeMessage
    is HermesError.Unknown -> "Something went wrong while contacting Hermes. Please try again."
    else -> "Something went wrong while contacting Hermes. Please try again."
}
