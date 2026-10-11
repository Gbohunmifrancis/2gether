package app.heartpact.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.ApiResult
import app.heartpact.data.AppContainer
import app.heartpact.data.Couple
import app.heartpact.data.CoupleSide
import app.heartpact.data.CoupleStatus
import app.heartpact.ui.theme.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the app should be, decided from the stored session and, when reachable, the server. */
enum class Destination { Onboarding, Welcome, Pair, Home }

class AppViewModel(private val app: AppContainer) : ViewModel() {
    private val _destination = MutableStateFlow<Destination?>(null)
    /** Null until known; the splash keeps playing meanwhile. */
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    private val _couple = MutableStateFlow<Couple?>(null)
    val couple: StateFlow<Couple?> = _couple.asStateFlow()

    val session = app.session.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val side: StateFlow<Side> = app.session.session.map { if (it?.side == CoupleSide.Right) Side.Right else Side.Left }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Side.Left)

    private val _openTab = MutableStateFlow<String?>(null)
    /** A tab a notification asked for (the album nudge); Home switches to it and consumes it. */
    val openTab: StateFlow<String?> = _openTab.asStateFlow()
    fun openTab(name: String) { _openTab.value = name }
    fun tabOpened() { _openTab.value = null }

    /** An invite link that opened the app; consumed by the pair screen. */
    var pendingInviteToken: String? = null

    init {
        viewModelScope.launch { _destination.value = decide() }
    }

    fun openedInvite(token: String) {
        pendingInviteToken = token
        // Already signed in and still waiting for a partner: go straight to pairing with it.
        if (_destination.value == Destination.Home || _destination.value == null) return
        if (session.value != null) _destination.value = Destination.Pair
    }

    /** After signing in or up, or after linking: re-read the couple and move on. */
    fun refresh() = viewModelScope.launch { _destination.value = decide() }

    fun finishOnboarding() = viewModelScope.launch {
        app.session.markOnboardingSeen()
        _destination.value = Destination.Welcome
    }

    fun signOut() = viewModelScope.launch {
        app.auth.signOut()
        // Photos waiting on this phone belong to this account's album: never sync them into another's.
        app.albumQueue.clear()
        _couple.value = null
        _destination.value = Destination.Welcome
    }

    private suspend fun decide(): Destination {
        val session = app.session.load() ?: return if (app.session.onboardingSeen.first()) Destination.Welcome else Destination.Onboarding
        // Ask the server, but never hold the splash for long: fall back to what we last knew.
        val fresh = withTimeoutOrNull(4_000) { app.couple.current(session.user.id) }
        val couple = (fresh as? ApiResult.Ok)?.value
        if (couple != null) _couple.value = couple
        if (fresh is ApiResult.Failure && fresh.status == 401) {
            app.auth.signOut()
            app.albumQueue.clear()
            return Destination.Welcome
        }
        val status = couple?.status ?: session.coupleStatus
        return if (status == CoupleStatus.Active) Destination.Home else Destination.Pair
    }
}
