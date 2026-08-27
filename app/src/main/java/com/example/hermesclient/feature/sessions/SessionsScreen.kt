package com.example.hermesclient.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.hermesclient.domain.model.ChatSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SessionsRoute(
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.navigationEvents.collect { event ->
                when (event) {
                    is SessionsNavigationEvent.OpenSession -> onOpenSession(event.sessionId)
                }
            }
        }
    }

    SessionsScreen(
        state = uiState.value,
        onSettingsClick = onOpenSettings,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onCreateSession = viewModel::createSession,
        onSessionClick = viewModel::openSession,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onSettingsClick: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onCreateSession: () -> Unit,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRefreshing = state.isRefreshing
    val isCreating = state.isCreating
    val actionsEnabled = state is SessionsUiState.Content || state is SessionsUiState.Empty

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                actions = {
                    TooltipIconButton(
                        label = "Refresh conversations",
                        enabled = actionsEnabled && !isRefreshing,
                        onClick = onRefresh,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                    TooltipIconButton(
                        label = "Settings",
                        onClick = onSettingsClick,
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                    }
                    TooltipIconButton(
                        label = if (isCreating) "Creating conversation" else "New conversation",
                        enabled = actionsEnabled && !isCreating,
                        onClick = onCreateSession,
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when (state) {
                SessionsUiState.Loading -> LoadingContent()
                is SessionsUiState.Error -> FullScreenError(
                    message = state.message,
                    onRetry = onRetry,
                )
                is SessionsUiState.Empty -> EmptyContent(
                    errorMessage = state.errorMessage,
                    onRetry = onRefresh,
                    onCreateSession = onCreateSession,
                    createEnabled = !state.isCreating,
                )
                is SessionsUiState.Content -> SessionList(
                    sessions = state.sessions,
                    errorMessage = state.errorMessage,
                    onRetry = onRefresh,
                    onSessionClick = onSessionClick,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "Loading conversations" },
        )
    }
}

@Composable
private fun FullScreenError(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Could not load conversations",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyContent(
    errorMessage: String?,
    onRetry: () -> Unit,
    onCreateSession: () -> Unit,
    createEnabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        errorMessage?.let { ErrorBanner(message = it, onRetry = onRetry) }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Start a new conversation with Hermes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onCreateSession,
                enabled = createEnabled,
            ) {
                Text("New conversation")
            }
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<ChatSession>,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSessionClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        errorMessage?.let { message ->
            item(key = "sessions-error") {
                ErrorBanner(message = message, onRetry = onRetry)
            }
        }
        items(
            items = sessions,
            key = ChatSession::id,
        ) { session ->
            SessionRow(session = session, onClick = { onSessionClick(session.id) })
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    onClick: () -> Unit,
) {
    val title = session.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled conversation"
    val timestamp = session.updatedAt ?: session.createdAt

    ListItem(
        headlineContent = {
            Text(
                text = title,
                maxLines = 2,
            )
        },
        supportingContent = timestamp?.let { instant ->
            { Text(formatSessionTimestamp(instant)) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
            content = content,
        )
    }
}

private fun formatSessionTimestamp(instant: Instant): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(instant)

private val SessionsUiState.isRefreshing: Boolean
    get() = when (this) {
        is SessionsUiState.Content -> isRefreshing
        is SessionsUiState.Empty -> isRefreshing
        else -> false
    }

private val SessionsUiState.isCreating: Boolean
    get() = when (this) {
        is SessionsUiState.Content -> isCreating
        is SessionsUiState.Empty -> isCreating
        else -> false
    }
