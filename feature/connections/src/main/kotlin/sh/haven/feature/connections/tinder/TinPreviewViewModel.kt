package sh.haven.feature.connections.tinder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject

data class TinPreviewState(
    val cards: Map<Pair<String, String>, TinShellCard> = emptyMap(),
    val lastFetchAt: Long? = null,
    val lastFetchOk: Boolean = false,
    val killedKeys: Set<Pair<String, String>> = emptySet()
)

sealed interface KillPrompt {
    data class ConfirmKill(val key: Pair<String, String>) : KillPrompt
    data class ConfirmForce(val key: Pair<String, String>, val serverMsg: String) : KillPrompt
    data class TypeName(val key: Pair<String, String>, val serverMsg: String) : KillPrompt
}

@HiltViewModel
class TinPreviewViewModel @Inject constructor(
    private val client: TinPreviewClient,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TinPreviewState())
    val state: StateFlow<TinPreviewState> = _state.asStateFlow()

    private val _killPrompt = MutableStateFlow<KillPrompt?>(null)
    val killPrompt: StateFlow<KillPrompt?> = _killPrompt.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private var pollingJob: Job? = null

    fun startPolling() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchOnce()
                delay(10_000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun fetchOnce() {
        val baseUrl = try {
            preferencesRepository.tinHubBaseUrl.first()
        } catch (e: Exception) {
            "https://tin.tail7f125e.ts.net/api"
        }
        try {
            val list = client.fetchShells(baseUrl)
            val mapped = list.associateBy { Pair(it.host, it.name) }
            _state.update { old ->
                old.copy(
                    cards = mapped,
                    lastFetchAt = System.currentTimeMillis(),
                    lastFetchOk = true
                )
            }
        } catch (e: Exception) {
            _state.update { old ->
                old.copy(
                    lastFetchOk = false
                )
            }
        }
    }

    private var accumulatedConfirmName: String? = null
    private var accumulatedForce: Boolean = false

    fun requestKill(key: Pair<String, String>) {
        accumulatedConfirmName = null
        accumulatedForce = false
        _killPrompt.value = KillPrompt.ConfirmKill(key)
    }

    fun confirmKill(key: Pair<String, String>, force: Boolean, confirmName: String?) {
        if (force) {
            accumulatedForce = true
        }
        if (confirmName != null) {
            accumulatedConfirmName = confirmName
        }
        viewModelScope.launch {
            val baseUrl = try {
                preferencesRepository.tinHubBaseUrl.first()
            } catch (e: Exception) {
                "https://tin.tail7f125e.ts.net/api"
            }
            
            // Resolve delete base URL
            val card = _state.value.cards[key]
            val deleteBase = if (card != null) {
                TinPreviewClient.deleteBaseFor(card, baseUrl)
            } else {
                baseUrl
            }

            val name = key.second
            val result = client.deleteShell(
                deleteBase = deleteBase,
                name = name,
                force = accumulatedForce,
                confirm = accumulatedConfirmName != null
            )

            when (result) {
                is TinDeleteResult.Ok -> {
                    _killPrompt.value = null
                    _state.update { old ->
                        old.copy(killedKeys = old.killedKeys + key)
                    }
                    _errorEvents.tryEmit("Successfully killed session $name")
                    fetchOnce() // immediate fetch
                }
                is TinDeleteResult.NeedsForce -> {
                    _killPrompt.value = KillPrompt.ConfirmForce(key, result.msg)
                }
                is TinDeleteResult.NeedsConfirm -> {
                    _killPrompt.value = KillPrompt.TypeName(key, result.msg)
                }
                is TinDeleteResult.Error -> {
                    _killPrompt.value = null
                    _errorEvents.tryEmit("Error: ${result.msg}")
                }
            }
        }
    }

    fun dismissKill() {
        _killPrompt.value = null
        accumulatedConfirmName = null
        accumulatedForce = false
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
