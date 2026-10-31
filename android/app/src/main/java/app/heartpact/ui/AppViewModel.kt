package app.heartpact.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.billing.PlusRestore
import app.heartpact.data.ApiResult
import app.heartpact.data.AppContainer
import app.heartpact.data.Couple
import app.heartpact.data.CoupleSide
import app.heartpact.data.CoupleStatus
import app.heartpact.alarm.AlarmScheduler
import app.heartpact.location.LocationShareService
import app.heartpact.location.LocationStop
import app.heartpact.push.Push
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
        // The partner ended the pact, or this account was deleted on another phone: decide again.
        viewModelScope.launch { app.pushEvents.collect { if (it in setOf("coupleEnded", "accountDeleted", "breakup")) refresh() } }
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
        // Sharing ends with the session, on this phone at once, online or not, before anything waits on the network.
        if (LocationShareService.running) LocationShareService.stop(app.appContext, tellServer = false)
        withTimeoutOrNull(5_000) { Push.unregister(app) }
        // Then the server hears it, before the hub and the token go, and stops it on the person's other phones too.
        // If it can't be reached, a retry carrying this session's token keeps trying, and the server's lease ends
        // the share meanwhile. Sent even when this phone isn't sharing: another may have been.
        LocationStop.send(app)
        app.live.stopAll()
        app.auth.signOut()
        stopAlarms()
        // Photos waiting on this phone belong to this account's album: never sync them into another's.
        app.albumQueue.clear()
        _couple.value = null
        _destination.value = Destination.Welcome
    }

    /** Signed out or no longer linked: nothing may ring for a pact that no longer applies here. */
    private fun stopAlarms() {
        AlarmScheduler.apply(app.appContext, emptyList())
        // Signed out or unlinked: the server already ended the share (or will, by its lease), so this stops it here only.
        if (LocationShareService.running) LocationShareService.stop(app.appContext, tellServer = false)
    }

    private suspend fun decide(): Destination {
        val session = app.session.load() ?: return if (app.session.onboardingSeen.first()) Destination.Welcome else Destination.Onboarding
        // Ask the server, but never hold the splash for long: fall back to what we last knew.
        val fresh = withTimeoutOrNull(4_000) { app.couple.current(session.user.id) }
        val couple = (fresh as? ApiResult.Ok)?.value
        if (couple != null) _couple.value = couple
        if (fresh is ApiResult.Failure && fresh.status == 401) {
            app.auth.signOut()
            stopAlarms()
            app.albumQueue.clear()
            return Destination.Welcome
        }
        // The account may have changed elsewhere (a new picture from another phone): keep the stored copy current.
        viewModelScope.launch { app.auth.refreshMe() }
        // Re-register on every start: tokens rotate, and the server keeps only the newest installs.
        viewModelScope.launch { Push.register(app) }
        // The hub joins the couple's group on connect: reconnect now that sign-in or linking changed it.
        app.live.restart()
        // A purchase the server never heard about (the app closed mid-purchase, a payment that cleared
        // later) is verified now; Play refunds anything unacknowledged after three days.
        viewModelScope.launch { runCatching { PlusRestore.run(app.playStore, app.plus, session.user.id, onlyUnacknowledged = true) } }
        val status = couple?.status ?: session.coupleStatus
        if (status != CoupleStatus.Active) stopAlarms()
        return if (status == CoupleStatus.Active) Destination.Home else Destination.Pair
    }
}
