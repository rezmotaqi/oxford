package com.example.hermesclient.feature.chat

import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ChatSession
import com.example.hermesclient.domain.model.ApprovalChoice

sealed interface ChatItem {
    val key: String

    data class Message(
        override val key: String,
        val message: ChatMessage,
        val isStreaming: Boolean = false,
    ) : ChatItem

    data class ToolActivity(
        override val key: String,
        val toolName: String,
        val state: ToolState,
        val preview: String? = null,
    ) : ChatItem

    data class Approval(
        override val key: String,
        val runId: String,
        val command: String,
        val description: String,
        val choices: Set<ApprovalChoice>,
        val state: ApprovalState = ApprovalState.Pending,
    ) : ChatItem
}

sealed interface ApprovalState {
    data object Pending : ApprovalState

    data class Responding(val choice: ApprovalChoice) : ApprovalState

    data class Resolved(val choice: ApprovalChoice) : ApprovalState

    data class Failed(val message: String) : ApprovalState
}

sealed interface ToolState {
    data object InProgress : ToolState

    data class Completed(
        val succeeded: Boolean,
    ) : ToolState
}

sealed interface StreamingState {
    data object Idle : StreamingState
    data object Streaming : StreamingState
    data object Stopping : StreamingState

    data class Failed(
        val message: String,
    ) : StreamingState
}

sealed interface ChatLoadState {
    data object Loading : ChatLoadState
    data object Ready : ChatLoadState

    data class Failed(
        val message: String,
    ) : ChatLoadState
}

data class ChatUiState(
    val session: ChatSession? = null,
    val items: List<ChatItem> = emptyList(),
    val loadState: ChatLoadState = ChatLoadState.Loading,
    val streamingState: StreamingState = StreamingState.Idle,
    val input: String = "",
    val activeRunId: String? = null,
    val actionError: String? = null,
) {
    val canSend: Boolean
        get() = loadState == ChatLoadState.Ready &&
            streamingState != StreamingState.Streaming &&
            streamingState != StreamingState.Stopping &&
            input.isNotBlank()

    val canStop: Boolean
        get() = streamingState == StreamingState.Streaming && activeRunId != null
}
