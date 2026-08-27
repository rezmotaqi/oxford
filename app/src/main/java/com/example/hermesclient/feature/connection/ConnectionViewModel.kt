package com.example.hermesclient.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hermesclient.domain.model.HermesCapabilities
import com.example.hermesclient.domain.model.HermesError
import com.example.hermesclient.domain.repository.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ConnectionForm(
    val serverUrl: String = "",
    val apiKey: String = "",
    val hasSavedApiKey: Boolean = false,
    val isApiKeyVisible: Boolean = false,
) {
    val canTest: Boolean
        get() = serverUrl.isNotBlank() && (apiKey.isNotBlank() || hasSavedApiKey)
}

sealed interface ConnectionUiState {
    val form: ConnectionForm

    data class Loading(
        override val form: ConnectionForm = ConnectionForm(),
    ) : ConnectionUiState

    data class Editing(
        override val form: ConnectionForm,
    ) : ConnectionUiState

    data class Testing(
        override val form: ConnectionForm,
    ) : ConnectionUiState

    data class Connected(
        override val form: ConnectionForm,
        val capabilities: HermesCapabilities,
    ) : ConnectionUiState

    data class Error(
        override val form: ConnectionForm,
        val message: String,
    ) : ConnectionUiState
}

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val repository: ConnectionRepository,
) : ViewModel() {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<ConnectionUiState>(
        ConnectionUiState.Loading(),
    )
    val state: kotlinx.coroutines.flow.StateFlow<ConnectionUiState> = mutableState

    private var testJob: Job? = null

    init {
        loadSavedConfiguration()
    }

    fun updateServerUrl(value: String) {
        updateForm { copy(serverUrl = value) }
    }

    fun updateApiKey(value: String) {
        updateForm { copy(apiKey = value) }
    }

    fun toggleApiKeyVisibility() {
        updateForm { copy(isApiKeyVisible = !isApiKeyVisible) }
    }

    fun testConnection() {
        if (mutableState.value is ConnectionUiState.Testing) return

        val form = mutableState.value.form
        when {
            form.serverUrl.isBlank() -> {
                mutableState.value = ConnectionUiState.Error(form, "Enter a Hermes server URL.")
                return
            }

            form.apiKey.isBlank() && !form.hasSavedApiKey -> {
                mutableState.value = ConnectionUiState.Error(form, "Enter your Hermes API key.")
                return
            }
        }

        mutableState.value = ConnectionUiState.Testing(form)
        testJob = viewModelScope.launch {
            val result = try {
                repository.testAndSave(
                    baseUrl = form.serverUrl,
                    apiKey = form.apiKey,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }

            result.fold(
                onSuccess = { capabilities -> handleConnectionSuccess(form, capabilities) },
                onFailure = { error ->
                    mutableState.value = ConnectionUiState.Error(form, error.userMessage())
                },
            )
        }
    }

    private fun loadSavedConfiguration() {
        viewModelScope.launch {
            mutableState.value = try {
                val config = repository.getSavedConfig()
                ConnectionUiState.Editing(
                    ConnectionForm(
                        serverUrl = config?.baseUrl.orEmpty(),
                        apiKey = "",
                        hasSavedApiKey = !config?.apiKey.isNullOrEmpty(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                ConnectionUiState.Error(
                    form = ConnectionForm(),
                    message = "The saved connection could not be loaded. Enter the connection details again.",
                )
            }
        }
    }

    private fun handleConnectionSuccess(
        previousForm: ConnectionForm,
        capabilities: HermesCapabilities,
    ) {
        val unsupportedMessage = when {
            !capabilities.sessionsSupported ->
                "This Hermes server does not support sessions. Please update Hermes Agent."

            !capabilities.runsSupported ->
                "This Hermes server does not support controllable runs. Please update Hermes Agent."

            else -> null
        }

        if (unsupportedMessage != null) {
            mutableState.value = ConnectionUiState.Error(previousForm, unsupportedMessage)
            return
        }

        mutableState.value = ConnectionUiState.Connected(
            form = previousForm.copy(
                apiKey = "",
                hasSavedApiKey = true,
                isApiKeyVisible = false,
            ),
            capabilities = capabilities,
        )
    }

    private fun updateForm(transform: ConnectionForm.() -> ConnectionForm) {
        val updated = mutableState.value.form.transform()
        mutableState.value = ConnectionUiState.Editing(updated)
    }

    override fun onCleared() {
        testJob?.cancel()
    }
}

private fun Throwable.userMessage(): String = when (this) {
    HermesError.Unauthorized -> "The API key was rejected. Check the key and try again."
    HermesError.Unreachable -> "Hermes could not be reached. Check the server URL and network connection."
    HermesError.Timeout -> "The connection timed out. Check the server and try again."
    HermesError.UnsupportedServer -> "This Hermes server is not compatible. Please update Hermes Agent."
    HermesError.SessionsUnsupported ->
        "This Hermes server does not support sessions. Please update Hermes Agent."
    HermesError.SessionStreamingUnsupported ->
        "This Hermes server does not support session streaming. Please update Hermes Agent."
    HermesError.InvalidUrl -> "Enter a valid HTTPS Hermes server URL."
    HermesError.MissingApiKey -> "Enter your Hermes API key."
    is HermesError.ServerError -> "Hermes returned a server error (HTTP $statusCode). Try again shortly."
    is HermesError.StreamFailure -> "Hermes could not complete the connection test."
    is HermesError.Unknown -> "The connection test failed. Check the details and try again."
    else -> "The connection test failed. Check the details and try again."
}
