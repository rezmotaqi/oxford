package com.example.hermesclient.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hermesclient.domain.model.ChatSession
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SessionsUiState {
    data object Loading : SessionsUiState

    data class Empty(
        val isRefreshing: Boolean = false,
        val isCreating: Boolean = false,
        val errorMessage: String? = null,
    ) : SessionsUiState

    data class Content(
        val sessions: List<ChatSession>,
        val isRefreshing: Boolean = false,
        val isCreating: Boolean = false,
        val errorMessage: String? = null,
    ) : SessionsUiState

    data class Error(
        val message: String,
    ) : SessionsUiState
}

sealed interface SessionsNavigationEvent {
    data class OpenSession(
        val sessionId: String,
    ) : SessionsNavigationEvent
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SessionsUiState>(SessionsUiState.Loading)
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val navigationChannel = Channel<SessionsNavigationEvent>(Channel.BUFFERED)
    val navigationEvents = navigationChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var createJob: Job? = null

    init {
        loadSessions(showFullScreenLoading = true)
    }

    fun refresh() {
        loadSessions(showFullScreenLoading = false)
    }

    fun retry() {
        loadSessions(showFullScreenLoading = true)
    }

    fun openSession(sessionId: String) {
        if (sessionId.isBlank()) return

        viewModelScope.launch {
            navigationChannel.send(SessionsNavigationEvent.OpenSession(sessionId))
        }
    }

    fun createSession() {
        val current = _uiState.value
        val canCreate = current is SessionsUiState.Content || current is SessionsUiState.Empty
        if (createJob?.isActive == true || !canCreate) return

        setCreating(isCreating = true)
        createJob = viewModelScope.launch {
            sessionRepository.createSession()
                .onSuccess { session ->
                    addCreatedSession(session)
                    navigationChannel.send(SessionsNavigationEvent.OpenSession(session.id))
                }
                .onFailure { error ->
                    setCreateFailure(error.toUserMessage())
                }
        }
    }

    private fun loadSessions(showFullScreenLoading: Boolean) {
        if (loadJob?.isActive == true) return

        if (showFullScreenLoading) {
            _uiState.value = SessionsUiState.Loading
        } else {
            val refreshStarted = _uiState.value.updateForRefresh()
            if (!refreshStarted) return
        }

        loadJob = viewModelScope.launch {
            sessionRepository.getSessions()
                .onSuccess(::setSessions)
                .onFailure { error -> setLoadFailure(error, showFullScreenLoading) }
        }
    }

    private fun setSessions(sessions: List<ChatSession>) {
        _uiState.value = if (sessions.isEmpty()) {
            SessionsUiState.Empty()
        } else {
            SessionsUiState.Content(sessions = sessions.toList())
        }
    }

    private fun setLoadFailure(error: Throwable, showFullScreenError: Boolean) {
        val message = error.toUserMessage()
        _uiState.update { current ->
            when {
                showFullScreenError -> SessionsUiState.Error(message)
                current is SessionsUiState.Content -> current.copy(
                    isRefreshing = false,
                    errorMessage = message,
                )
                current is SessionsUiState.Empty -> current.copy(
                    isRefreshing = false,
                    errorMessage = message,
                )
                else -> SessionsUiState.Error(message)
            }
        }
    }

    private fun setCreating(isCreating: Boolean) {
        _uiState.update { current ->
            when (current) {
                is SessionsUiState.Content -> current.copy(
                    isCreating = isCreating,
                    errorMessage = null,
                )
                is SessionsUiState.Empty -> current.copy(
                    isCreating = isCreating,
                    errorMessage = null,
                )
                else -> current
            }
        }
    }

    private fun addCreatedSession(session: ChatSession) {
        _uiState.update { current ->
            when (current) {
                is SessionsUiState.Content -> current.copy(
                    sessions = listOf(session) + current.sessions.filterNot { it.id == session.id },
                    isCreating = false,
                    errorMessage = null,
                )
                is SessionsUiState.Empty -> SessionsUiState.Content(sessions = listOf(session))
                else -> current
            }
        }
    }

    private fun setCreateFailure(message: String) {
        _uiState.update { current ->
            when (current) {
                is SessionsUiState.Content -> current.copy(
                    isCreating = false,
                    errorMessage = message,
                )
                is SessionsUiState.Empty -> current.copy(
                    isCreating = false,
                    errorMessage = message,
                )
                else -> current
            }
        }
    }

    private fun SessionsUiState.updateForRefresh(): Boolean = when (this) {
        is SessionsUiState.Content -> {
            _uiState.value = copy(isRefreshing = true, errorMessage = null)
            true
        }
        is SessionsUiState.Empty -> {
            _uiState.value = copy(isRefreshing = true, errorMessage = null)
            true
        }
        is SessionsUiState.Error -> {
            _uiState.value = SessionsUiState.Loading
            true
        }
        SessionsUiState.Loading -> false
    }
}

private fun Throwable.toUserMessage(): String = when (this) {
    HermesError.Unauthorized -> "Your API key is no longer valid. Update it in Settings."
    HermesError.Unreachable -> "Hermes is unreachable. Check the server address and connection."
    HermesError.Timeout -> "Hermes took too long to respond. Try again."
    HermesError.UnsupportedServer -> "This Hermes server does not support sessions. Please update Hermes Agent."
    HermesError.SessionsUnsupported -> "This Hermes server does not support sessions. Please update Hermes Agent."
    HermesError.SessionStreamingUnsupported ->
        "This Hermes server does not support session streaming. Please update Hermes Agent."
    HermesError.InvalidUrl -> "The Hermes server URL is invalid. Update it in Settings."
    HermesError.MissingApiKey -> "An API key is required. Update it in Settings."
    is HermesError.ServerError -> "Hermes returned a server error ($statusCode). Try again."
    is HermesError.StreamFailure -> "The Hermes connection was interrupted. Try again."
    is HermesError.Unknown -> "Something went wrong while contacting Hermes. Try again."
    else -> "Something went wrong while contacting Hermes. Try again."
}
