package app.heartpact.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.AiRepository
import app.heartpact.data.AiStatus
import app.heartpact.data.ApiResult
import app.heartpact.data.WeeklyRecap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Us shows of the AI features: whether Plan something is on, and this week's recap. Offline keeps what it had. */
data class UsAiUi(val status: AiStatus = AiStatus(), val recap: WeeklyRecap? = null)

class UsAiViewModel(private val ai: AiRepository, events: Flow<String> = emptyFlow()) : ViewModel() {
    private val _ui = MutableStateFlow(UsAiUi())
    val ui: StateFlow<UsAiUi> = _ui.asStateFlow()

    init {
        refresh()
        // The recap's push: the card shows at once.
        viewModelScope.launch { events.collect { if (it == "weeklyRecap") refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val status = ai.status()
        if (status is ApiResult.Ok) _ui.update { it.copy(status = status.value) }
        if (status is ApiResult.Ok && !status.value.weeklyRecap) {
            _ui.update { it.copy(recap = null) }
            return@launch
        }
        when (val recap = ai.recap()) {
            is ApiResult.Ok -> _ui.update { it.copy(recap = recap.value) }
            // A server without recaps (404) has none to show.
            is ApiResult.Failure -> if (recap.status == 404) _ui.update { it.copy(recap = null) }
            ApiResult.Offline -> Unit
        }
    }
}
