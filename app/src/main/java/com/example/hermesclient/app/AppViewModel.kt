package com.example.hermesclient.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hermesclient.domain.repository.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StartupState {
    data object Loading : StartupState
    data object NeedsConnection : StartupState
    data object Ready : StartupState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<StartupState>(StartupState.Loading)
    val state: StateFlow<StartupState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            mutableState.value = try {
                val config = connectionRepository.getSavedConfig()
                if (config != null && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) {
                    StartupState.Ready
                } else {
                    StartupState.NeedsConnection
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                StartupState.NeedsConnection
            }
        }
    }
}
