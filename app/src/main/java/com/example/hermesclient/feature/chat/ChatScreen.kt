package com.example.hermesclient.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hermesclient.domain.model.ChatMessage
import com.example.hermesclient.domain.model.ApprovalChoice
import com.example.hermesclient.domain.model.MessageRole
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ChatRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        onInputChange = viewModel::updateInput,
        onSend = viewModel::sendMessage,
        onStop = viewModel::stopRun,
        onApproval = viewModel::respondToApproval,
        onRetry = viewModel::retryLoad,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onApproval: (String, ApprovalChoice) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.session?.title?.takeIf(String::isNotBlank) ?: "Hermes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (state.loadState == ChatLoadState.Ready) {
                ChatInput(
                    value = state.input,
                    enabled = state.streamingState !is StreamingState.Streaming &&
                        state.streamingState !is StreamingState.Stopping,
                    canSend = state.canSend,
                    canStop = state.canStop,
                    isStopping = state.streamingState == StreamingState.Stopping,
                    onValueChange = onInputChange,
                    onSend = {
                        onSend()
                        focusManager.clearFocus()
                    },
                    onStop = onStop,
                )
            }
        },
    ) { contentPadding ->
        when (val loadState = state.loadState) {
            ChatLoadState.Loading -> LoadingContent(
                modifier = Modifier.padding(contentPadding),
            )
            is ChatLoadState.Failed -> LoadErrorContent(
                message = loadState.message,
                onRetry = onRetry,
                modifier = Modifier.padding(contentPadding),
            )
            ChatLoadState.Ready -> ConversationContent(
                items = state.items,
                streamingState = state.streamingState,
                actionError = state.actionError,
                onApproval = onApproval,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}

@Composable
private fun ConversationContent(
    items: List<ChatItem>,
    streamingState: StreamingState,
    actionError: String?,
    onApproval: (String, ApprovalChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    val latestContent = (items.lastOrNull() as? ChatItem.Message)?.message?.content
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    autoScrollEnabled = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 ||
                layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1
        }
            .distinctUntilChanged()
            .collect { isAtBottom ->
                if (isAtBottom) autoScrollEnabled = true
            }
    }
    LaunchedEffect(items.size, latestContent, streamingState, actionError, autoScrollEnabled) {
        if (autoScrollEnabled && items.isNotEmpty()) {
            val targetIndex = if (streamingState is StreamingState.Failed) {
                items.size
            } else {
                items.lastIndex
            }
            listState.scrollToItem(targetIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Text(
                text = "Start a conversation with Hermes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(userScrollConnection),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(items, key = ChatItem::key) { item ->
                    when (item) {
                        is ChatItem.Message -> MessageItem(item)
                        is ChatItem.ToolActivity -> ToolActivityItem(item)
                        is ChatItem.Approval -> ApprovalItem(item, onApproval)
                    }
                }
                if (streamingState is StreamingState.Failed) {
                    item(key = "stream-error") {
                        InlineError(streamingState.message)
                    }
                }
                if (actionError != null) {
                    item(key = "action-error") {
                        InlineError(actionError)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalItem(
    item: ChatItem.Approval,
    onApproval: (String, ApprovalChoice) -> Unit,
) {
    val resolved = item.state as? ApprovalState.Resolved
    val responding = item.state as? ApprovalState.Responding
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Approval required", style = MaterialTheme.typography.titleSmall)
            Text(item.description, style = MaterialTheme.typography.bodyMedium)
            if (item.command.isNotBlank()) {
                Text(
                    item.command,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            when (val approvalState = item.state) {
                ApprovalState.Pending, is ApprovalState.Failed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ApprovalChoice.ONCE in item.choices) {
                            Button(onClick = { onApproval(item.key, ApprovalChoice.ONCE) }) {
                                Text("Allow once")
                            }
                        }
                        if (ApprovalChoice.DENY in item.choices) {
                            OutlinedButton(onClick = { onApproval(item.key, ApprovalChoice.DENY) }) {
                                Text("Deny")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ApprovalChoice.SESSION in item.choices) {
                            OutlinedButton(onClick = { onApproval(item.key, ApprovalChoice.SESSION) }) {
                                Text("Allow for session")
                            }
                        }
                        if (ApprovalChoice.ALWAYS in item.choices) {
                            OutlinedButton(onClick = { onApproval(item.key, ApprovalChoice.ALWAYS) }) {
                                Text("Always allow")
                            }
                        }
                    }
                    if (approvalState is ApprovalState.Failed) {
                        Text(
                            approvalState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is ApprovalState.Responding -> Text("Sending ${approvalState.choice.label()}...")
                is ApprovalState.Resolved -> Text(
                    "${resolved?.choice?.label() ?: "Response"} sent",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun MessageItem(item: ChatItem.Message) {
    val isUser = item.message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = roleLabel(item.message),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            ) {
                Text(
                    text = item.message.content.ifEmpty { if (item.isStreaming) "Thinking..." else "" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolActivityItem(item: ChatItem.ToolActivity) {
    val completed = item.state as? ToolState.Completed
    val icon = when {
        completed == null -> Icons.Filled.HourglassTop
        completed.succeeded -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Error
    }
    val tint = when {
        completed == null -> MaterialTheme.colorScheme.primary
        completed.succeeded -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                text = item.toolName,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    completed == null -> "Running"
                    completed.succeeded -> "Done"
                    else -> "Failed"
                },
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

@Composable
private fun ChatInput(
    value: String,
    enabled: Boolean,
    canSend: Boolean,
    canStop: Boolean,
    isStopping: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Hermes...") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            )
            if (canStop || isStopping) {
                IconButton(onClick = onStop, enabled = canStop, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.StopCircle, contentDescription = "Stop")
                }
            } else {
                IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoadErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.size(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun InlineError(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun roleLabel(message: ChatMessage): String = when (message.role) {
    MessageRole.USER -> "You"
    MessageRole.ASSISTANT -> "Hermes"
    MessageRole.SYSTEM -> "System"
    MessageRole.TOOL -> message.toolName ?: "Tool"
    MessageRole.UNKNOWN -> "Message"
}

private fun ApprovalChoice.label(): String = when (this) {
    ApprovalChoice.ONCE -> "Allow once"
    ApprovalChoice.SESSION -> "Allow for session"
    ApprovalChoice.ALWAYS -> "Always allow"
    ApprovalChoice.DENY -> "Denied"
}
