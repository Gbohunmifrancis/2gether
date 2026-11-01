package app.heartpact.ui

import androidx.compose.runtime.CompositionLocalProvider
import app.heartpact.ui.us.ProfileScreen
import app.heartpact.ui.components.Face
import app.heartpact.ui.components.LocalAvatars
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.heartpact.ui.graphics.Icon3D
import app.heartpact.alarm.AlarmActivity
import app.heartpact.alarm.AlarmNotifier
import app.heartpact.alarm.AlarmSlot
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.heartpact.data.CoupleSide
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.material.icons.Icons
import app.heartpact.billing.PlusOffers
import app.heartpact.ui.plus.PlusCelebrationDialog
import app.heartpact.ui.plus.PlusScreen
import app.heartpact.ui.plus.PlusUsRow
import app.heartpact.ui.plus.PlusSummaryViewModel
import app.heartpact.ui.plus.PlusViewModel
import app.heartpact.ui.components.ReadableScreen
import app.heartpact.ui.components.openInBrowser
import app.heartpact.push.PushInbox
import app.heartpact.push.BannerQueue
import app.heartpact.push.PushActions
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.ApiResult
import app.heartpact.ui.components.InAppBannerHost
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesViewModel
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.cycle.CycleActions
import app.heartpact.ui.cycle.CycleMode
import app.heartpact.ui.cycle.CyclePlan
import app.heartpact.ui.cycle.CycleScreen
import app.heartpact.ui.cycle.CycleViewModel
import app.heartpact.ui.pregnancy.PregnancyActions
import app.heartpact.ui.pregnancy.PregnancyScreen
import app.heartpact.ui.pregnancy.PregnancyText
import app.heartpact.ui.pregnancy.PregnancyViewModel
import app.heartpact.ui.map.LocationCard
import app.heartpact.ui.map.LocationViewModel
import app.heartpact.ui.map.MapActions
import app.heartpact.ui.map.MapScreen
import app.heartpact.ui.map.rememberShareLocation
import app.heartpact.ui.us.Chevron
import app.heartpact.ui.us.NotificationsScreen
import app.heartpact.ui.us.NotificationsViewModel
import app.heartpact.ui.us.QuestionScreen
import app.heartpact.ui.us.QuestionViewModel
import app.heartpact.ui.us.UsTab
import app.heartpact.ui.us.UsText
import app.heartpact.ui.us.UsViewModel
import app.heartpact.ui.ai.PlanScreen
import app.heartpact.ui.ai.PlanViewModel
import app.heartpact.ui.ai.UsAiViewModel
import app.heartpact.ui.ai.WeeklyRecapCard
import app.heartpact.ui.ai.WeeklyRecapScreen
import androidx.activity.compose.BackHandler
import app.heartpact.ui.auth.ForgotPasswordScreen
import app.heartpact.ui.auth.ForgotViewModel
import app.heartpact.ui.auth.GoogleFinishScreen
import app.heartpact.ui.auth.GoogleSignIn
import app.heartpact.ui.auth.GoogleViewModel
import app.heartpact.ui.food.FoodEntryScreen
import app.heartpact.ui.food.FoodEntryViewModel
import app.heartpact.ui.food.FoodScanScreen
import app.heartpact.ui.food.FoodScanViewModel
import app.heartpact.ui.food.FoodTab
import app.heartpact.ui.food.FoodViewModel
import java.io.File
import app.heartpact.ui.album.AlbumTab
import app.heartpact.ui.album.AlbumViewModel
import app.heartpact.ui.home.Tab
import app.heartpact.ui.theme.Side
import app.heartpact.ui.pacts.PactsTab
import app.heartpact.ui.pacts.PactsViewModel
import app.heartpact.ui.today.MyDare
import app.heartpact.ui.today.NewDareScreen
import app.heartpact.ui.today.NewDareViewModel
import app.heartpact.ui.today.TodayGames
import app.heartpact.ui.today.TodayGamesViewModel
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayViewModel
import app.heartpact.ui.watch.WatchLiveScreen
import app.heartpact.ui.watch.WatchLiveViewModel
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.heartpact.data.AppContainer
import app.heartpact.ui.auth.SignInScreen
import app.heartpact.ui.auth.SignInViewModel
import app.heartpact.ui.auth.SignUpScreen
import app.heartpact.ui.auth.SignUpViewModel
import app.heartpact.ui.auth.WelcomeScreen
import app.heartpact.ui.home.ConnectBanner
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.NeedsPartnerDialog
import app.heartpact.ui.onboarding.OnboardingScreen
import app.heartpact.ui.pair.PairActions
import app.heartpact.ui.pair.PairScreen
import app.heartpact.ui.pair.PairViewModel
import app.heartpact.ui.pair.ScanScreen
import app.heartpact.ui.auth.PasswordSaving
import androidx.compose.ui.platform.LocalAutofillManager
import app.heartpact.ui.splash.AnimatedSplash
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme
import kotlinx.coroutines.delay

private object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val WELCOME = "welcome"
    const val SIGN_UP = "signup"
    const val SIGN_IN = "signin"
    const val PAIR = "pair"
    const val SCAN = "pair/scan"
    const val HOME = "home"
    const val NEW_DARE = "newdare"
    const val WATCH = "watch/{id}"
    fun watch(occurrenceId: String) = "watch/$occurrenceId"
    const val FORGOT = "forgot?email={email}"
    fun forgot(email: String) = "forgot?email=" + Uri.encode(email)
    const val QUESTION = "question"
    const val PROFILE = "profile"
    const val CYCLE = "cycle"
    const val MAP = "map"
    const val GAMES = "games"
    const val PLUS = "plus"
    const val PREGNANCY = "pregnancy"
    const val NOTIFICATIONS = "notifications"
    const val PLAN = "plan"
    const val RECAP = "recap"
    const val FOOD_SCAN = "foodscan?uri={uri}"
    const val FOOD_ENTRY = "foodentry?id={id}"
    fun foodScan(uri: Uri) = "foodscan?uri=" + Uri.encode(uri.toString())
    fun foodEntry(id: String?) = if (id == null) "foodentry" else "foodentry?id=$id"
}

/** The pair screen's saved-state key for what the scanner read; [TYPE_INSTEAD] asks for the code boxes. */
private const val SCANNED = "scanned"
private const val TYPE_INSTEAD = "type"

private fun Destination.route() = when (this) {
    Destination.Onboarding -> Routes.ONBOARDING
    Destination.Welcome -> Routes.WELCOME
    Destination.Pair -> Routes.PAIR
    Destination.Home -> Routes.HOME
}

@Composable
fun HeartpactApp(container: AppContainer, appModel: AppViewModel) {
    val side by appModel.side.collectAsStateWithLifecycle()
    HeartpactTheme(night = container.theme.night(androidx.compose.foundation.isSystemInDarkTheme()), actor = side) {
        val nav = rememberNavController()
        val destination by appModel.destination.collectAsStateWithLifecycle()
        var splashDone by remember { mutableStateOf(false) }
        val entry by nav.currentBackStackEntryAsState()
        StatusBarIcons(dark = entry?.destination?.route != Routes.SPLASH && !Heartpact.colors.isNight)

        // Leave the splash once it has played and we know where to go; later decisions (sign out,
        // linking) replace the whole stack so Back never returns to a screen that no longer applies.
        LaunchedEffect(splashDone, destination) {
            val target = destination ?: return@LaunchedEffect
            if (!splashDone) return@LaunchedEffect
            val route = target.route()
            if (nav.currentDestination?.route != route) nav.resetTo(route)
        }

        // A game push (an invite, your turn, a game ended) opens the games over home, once the app is in.
        val requested by appModel.openTab.collectAsStateWithLifecycle()
        LaunchedEffect(requested, splashDone, destination, entry?.destination?.route) {
            if (requested != PushInbox.GAMES || !splashDone || destination != Destination.Home) return@LaunchedEffect
            val route = entry?.destination?.route ?: return@LaunchedEffect
            if (route == Routes.SPLASH) return@LaunchedEffect
            appModel.tabOpened()
            if (route != Routes.GAMES) nav.navigate(Routes.GAMES) { launchSingleTop = true }
        }

        // "{partner} started their squats" (an exerciseStarted push): the live view opens over home, once the app is in.
        LaunchedEffect(requested, splashDone, destination, entry?.destination?.route) {
            val occurrence = PushInbox.watchedOccurrence(requested) ?: return@LaunchedEffect
            if (!splashDone || destination != Destination.Home) return@LaunchedEffect
            val route = entry?.destination?.route ?: return@LaunchedEffect
            if (route == Routes.SPLASH) return@LaunchedEffect
            appModel.tabOpened()
            nav.navigate(Routes.watch(occurrence)) { launchSingleTop = true }
        }

        // A screen over Home a notification asked for ("watch/{occurrenceId}" when a dare goes live). An app without that
        // screen yet (the route isn't in the graph) opens Today instead.
        val requestedRoute by appModel.openRoute.collectAsStateWithLifecycle()
        LaunchedEffect(requestedRoute, splashDone, destination, entry?.destination?.route) {
            val wanted = requestedRoute ?: return@LaunchedEffect
            if (!splashDone || destination != Destination.Home) return@LaunchedEffect
            val route = entry?.destination?.route ?: return@LaunchedEffect
            if (route == Routes.SPLASH) return@LaunchedEffect
            appModel.routeOpened()
            if (runCatching { nav.navigate(wanted) { launchSingleTop = true } }.isFailure) appModel.openTab("today")
        }

        // What arrives while the app is open drops in as a banner, one at a time, obeying the person's notification switches.
        var banners by remember { mutableStateOf(BannerQueue()) }
        var notificationPrefs by remember { mutableStateOf<NotificationPreferences?>(null) }
        val lifecycle = LocalLifecycleOwner.current
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { (container.us.preferences() as? ApiResult.Ok)?.let { notificationPrefs = it.value } }
                container.notices.collect { notice -> if (PushActions.allowed(notice.type, notificationPrefs)) banners = banners.offer(notice) }
            }
        }

        // An invite link opened on Home before linking: the pair screen opens over Home with it.
        val pairRequested by appModel.pairRequested.collectAsStateWithLifecycle()
        LaunchedEffect(pairRequested, splashDone, destination, entry?.destination?.route) {
            if (!pairRequested || !splashDone || destination != Destination.Home) return@LaunchedEffect
            val route = entry?.destination?.route ?: return@LaunchedEffect
            if (route == Routes.SPLASH) return@LaunchedEffect
            appModel.pairOpened()
            if (route != Routes.PAIR) nav.navigate(Routes.PAIR) { launchSingleTop = true }
        }
        // Linked while on Home (from the pair screen over it, or the partner used this person's code): Home starts
        // afresh, so every tab picks up the partner.
        val linked by appModel.linked.collectAsStateWithLifecycle()
        var wasLinked by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(linked) {
            val before = wasLinked
            wasLinked = linked
            if (before == false && linked && splashDone && destination == Destination.Home) nav.resetTo(Routes.HOME)
        }

        CompositionLocalProvider(LocalAvatars provides container.avatars) {
        Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
            // Screens glide in from the side with a soft fade and a little scale; going back reverses it. The splash and
            // the root screens (home, welcome) cross-fade instead, since nothing is "behind" them.
            NavHost(nav, startDestination = Routes.SPLASH,
                enterTransition = { slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(320)) + scaleIn(tween(360), initialScale = 0.96f) },
                exitTransition = { slideOutHorizontally(tween(360, easing = FastOutSlowInEasing)) { -it / 8 } + fadeOut(tween(240)) },
                popEnterTransition = { slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(320)) },
                popExitTransition = { slideOutHorizontally(tween(360, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(240)) + scaleOut(tween(360), targetScale = 0.96f) },
            ) {
                composable(Routes.SPLASH) { AnimatedSplash(onFinished = { splashDone = true }) }
                composable(Routes.ONBOARDING) { ReadableScreen { OnboardingScreen(onDone = { appModel.finishOnboarding() }) } }
                composable(Routes.WELCOME) {
                    ReadableScreen {
                        val google: GoogleViewModel = viewModel(factory = viewModelFactory { initializer { GoogleViewModel(container.auth) } })
                        val googleState by google.state.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        val inviter by appModel.inviter.collectAsStateWithLifecycle()
                        if (googleState.finishing) {
                            BackHandler { google.cancel() }
                            GoogleFinishScreen(googleState, google::name, google::birthday, google::gender, google::country, { google.finish { appModel.refresh() } }, onBack = google::cancel,
                                invitedBy = inviter?.takeIf { it.isNotBlank() })
                        } else WelcomeScreen(
                            onCreateAccount = { nav.navigate(Routes.SIGN_UP) }, onSignIn = { nav.navigate(Routes.SIGN_IN) },
                            onGoogle = if (GoogleSignIn.available) ({ google.signIn(context) { appModel.refresh() } }) else null,
                            googleBusy = googleState.busy, note = googleState.note, invitedBy = inviter,
                        )
                    }
                }
                composable(Routes.FORGOT, arguments = listOf(navArgument("email") { type = NavType.StringType; defaultValue = "" })) { entry ->
                    ReadableScreen {
                        val email = entry.arguments?.getString("email").orEmpty()
                        val model: ForgotViewModel = viewModel(factory = viewModelFactory { initializer { ForgotViewModel(container.auth, email) } })
                        val state by model.state.collectAsStateWithLifecycle()
                        ForgotPasswordScreen(state, model::email, model::code, model::password, model::send, { model.reset { appModel.refresh() } },
                            onBack = { if (state.sent) model.back() else nav.popBackStack() })
                    }
                }
                composable(Routes.SIGN_UP) {
                    ReadableScreen {
                        val model: SignUpViewModel = viewModel(factory = viewModelFactory { initializer { SignUpViewModel(container.auth) } })
                        val state by model.state.collectAsStateWithLifecycle()
                        val google: GoogleViewModel = viewModel(factory = viewModelFactory { initializer { GoogleViewModel(container.auth) } })
                        val googleState by google.state.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        val activity = LocalActivity.current
                        val autofill = LocalAutofillManager.current
                        val inviter by appModel.inviter.collectAsStateWithLifecycle()
                        if (googleState.finishing) {
                            BackHandler { google.cancel() }
                            GoogleFinishScreen(googleState, google::name, google::birthday, google::gender, google::country, { google.finish { appModel.refresh() } }, onBack = google::cancel,
                                invitedBy = inviter?.takeIf { it.isNotBlank() })
                        } else {
                            // Back from "About you" returns to step 1 with everything kept.
                            BackHandler(enabled = state.step == 2) { model.back() }
                            SignUpScreen(state, model::edit, model::next, {
                                model.submit {
                                    // The password manager's own offer, once; not autofill's as well.
                                    autofill?.cancel()
                                    val form = model.state.value.form
                                    PasswordSaving.offer(activity, form.email, form.password)
                                    appModel.refresh()
                                }
                            }, onBack = { if (!model.back()) nav.popBackStack() },
                                onGoogle = if (GoogleSignIn.available) ({ google.signIn(context) { appModel.refresh() } }) else null,
                                googleBusy = googleState.busy, googleNote = googleState.note, invitedBy = inviter?.takeIf { it.isNotBlank() })
                        }
                    }
                }
                composable(Routes.SIGN_IN) {
                    ReadableScreen {
                        val model: SignInViewModel = viewModel(factory = viewModelFactory { initializer { SignInViewModel(container.auth) } })
                        val state by model.state.collectAsStateWithLifecycle()
                        val google: GoogleViewModel = viewModel(factory = viewModelFactory { initializer { GoogleViewModel(container.auth) } })
                        val googleState by google.state.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        val activity = LocalActivity.current
                        val autofill = LocalAutofillManager.current
                        val inviter by appModel.inviter.collectAsStateWithLifecycle()
                        if (googleState.finishing) {
                            BackHandler { google.cancel() }
                            GoogleFinishScreen(googleState, google::name, google::birthday, google::gender, google::country, { google.finish { appModel.refresh() } }, onBack = google::cancel,
                                invitedBy = inviter?.takeIf { it.isNotBlank() })
                        } else SignInScreen(state.copy(note = state.note ?: googleState.note), model::email, model::password, {
                                model.submit {
                                    autofill?.cancel()
                                    val signedIn = model.state.value
                                    PasswordSaving.offer(activity, signedIn.email, signedIn.password)
                                    appModel.refresh()
                                }
                            },
                            onForgot = { nav.navigate(Routes.forgot(state.email)) },
                            onCreateAccount = { nav.navigate(Routes.SIGN_UP) { popUpTo(Routes.WELCOME) } }, onBack = { nav.popBackStack() },
                            onGoogle = if (GoogleSignIn.available) ({ google.signIn(context) { appModel.refresh() } }) else null, googleBusy = googleState.busy,
                            invitedBy = inviter?.takeIf { it.isNotBlank() })
                    }
                }
                composable(Routes.PAIR) { entry ->
                    ReadableScreen {
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val userId = session?.user?.id ?: return@composable
                        val model: PairViewModel = viewModel(factory = viewModelFactory {
                            initializer {
                                // A partner's link that brought the person here is the first thing done.
                                val pending = appModel.pendingLink.value?.also { appModel.inviteTaken() }
                                PairViewModel(container.couple, userId, pending, container.pushEvents)
                            }
                        })
                        val state by model.state.collectAsStateWithLifecycle()
                        // A link opened while this screen is already up.
                        val pending by appModel.pendingLink.collectAsStateWithLifecycle()
                        LaunchedEffect(pending) { pending?.let { model.offerLink(it); appModel.inviteTaken() } }
                        // What the scanner read comes back here.
                        val scanned by entry.savedStateHandle.getStateFlow<String?>(SCANNED, null).collectAsStateWithLifecycle()
                        LaunchedEffect(scanned) {
                            scanned?.let { text ->
                                if (text == TYPE_INSTEAD) model.startEntering() else app.heartpact.ui.pair.InviteParsing.parse(text)?.let(model::use)
                                entry.savedStateHandle[SCANNED] = null
                            }
                        }
                        LaunchedEffect(state.linked) { if (state.linked) appModel.refresh() }
                        // Over Home ("Connect"), Back returns there; straight after signing up, "Connect later" goes there.
                        val fromHome = destination == Destination.Home
                        PairScreen(state, PairActions(
                            code = model::code, startEntering = model::startEntering, stopEntering = model::stopEntering, submitCode = model::submitCode,
                            retry = { model.loadInvite() }, newCode = model::newInvite, scan = { nav.navigate(Routes.SCAN) { launchSingleTop = true } },
                            use = model::use, confirmLink = model::confirmLink, declineLink = model::declineLink, celebrated = model::celebrated,
                            signOut = { appModel.signOut() },
                        ),
                            onLater = if (fromHome) null else ({ appModel.connectLater() }),
                            onBack = if (fromHome) ({ nav.popBackStack() }) else null)
                    }
                }
                composable(Routes.SCAN) {
                    ScanScreen(
                        onFound = { input ->
                            nav.previousBackStackEntry?.savedStateHandle?.set(SCANNED, when (input) {
                                is app.heartpact.ui.pair.InviteInput.Link -> app.heartpact.ui.pair.InviteParsing.link(input.token)
                                is app.heartpact.ui.pair.InviteInput.Code -> input.digits
                            })
                            nav.popBackStack()
                        },
                        onBack = { nav.popBackStack() },
                        onType = {
                            nav.previousBackStackEntry?.savedStateHandle?.set(SCANNED, TYPE_INSTEAD)
                            nav.popBackStack()
                        },
                    )
                }
                composable(Routes.HOME) {
                    val session by appModel.session.collectAsStateWithLifecycle()
                    val couple by appModel.couple.collectAsStateWithLifecycle()
                    val me = session?.user ?: return@composable
                    val partnerMember = couple?.members?.firstOrNull { it.userId != me.id }
                    val partnerSide = if (side == Side.Left) Side.Right else Side.Left
                    val partner = couple?.members?.firstOrNull { it.userId != me.id }?.displayName ?: tr("your partner")
                    val application = LocalContext.current.applicationContext as Application
                    val context = LocalContext.current
                    val todayModel: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(application, me.id, partner) } })
                    val todayUi by todayModel.ui.collectAsStateWithLifecycle()
                    // A game waiting on me (an invite, my turn), for Today's card: live from the couple hub, fresh on resume.
                    val todayGames: TodayGamesViewModel = viewModel(factory = viewModelFactory { initializer { TodayGamesViewModel(application) } })
                    val todayGamesUi by todayGames.ui.collectAsStateWithLifecycle()
                    // Back from Settings (permissions), a dare or a game: re-read readiness, today and what game waits.
                    LifecycleResumeEffect(Unit) {
                        todayModel.refresh()
                        todayGames.refresh()
                        onPauseOrDispose { }
                    }
                    val pactsModel: PactsViewModel = viewModel(factory = viewModelFactory { initializer { PactsViewModel(application, me.id) } })
                    val pactsUi by pactsModel.ui.collectAsStateWithLifecycle()
                    val albumModel: AlbumViewModel = viewModel(factory = viewModelFactory { initializer { AlbumViewModel(application) } })
                    val albumUi by albumModel.ui.collectAsStateWithLifecycle()
                    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { albumModel.add(it) }
                    val sides = couple?.members.orEmpty().associate { it.userId to if (it.side == CoupleSide.Right) Side.Right else Side.Left }
                    // Not linked yet ("Connect later"): what needs a partner asks to connect first instead of failing.
                    var askConnect by remember { mutableStateOf(false) }
                    val connect = { nav.navigate(Routes.PAIR) { launchSingleTop = true } }
                    fun withPartner(action: () -> Unit): () -> Unit = { if (linked) action() else askConnect = true }
                    // Linking sends no push: while not linked, look now and then for the partner having used our code.
                    if (!linked) LaunchedEffect(Unit) { while (true) { delay(15_000); appModel.checkLinked() } }
                    val requestedTab by appModel.openTab.collectAsStateWithLifecycle()
                    val foodModel: FoodViewModel = viewModel(factory = viewModelFactory { initializer { FoodViewModel(container.food, container.pushEvents) } })
                    val foodUi by foodModel.ui.collectAsStateWithLifecycle()
                    val usModel: UsViewModel = viewModel(factory = viewModelFactory { initializer { UsViewModel(container.us, container.pushEvents, container.auth) } })
                    val usUi by usModel.ui.collectAsStateWithLifecycle()
                    // The AI features on Us (docs/ai.md): this week's recap and Plan something, each only when the server offers it.
                    val usAi: UsAiViewModel = viewModel(key = "us-ai", factory = viewModelFactory { initializer { UsAiViewModel(container.ai, container.pushEvents) } })
                    val usAiUi by usAi.ui.collectAsStateWithLifecycle()
                    val locationModel: LocationViewModel = viewModel(key = "location", factory = viewModelFactory { initializer { LocationViewModel(application) } })
                    val locationUi by locationModel.ui.collectAsStateWithLifecycle()
                    val shareLocation = rememberShareLocation { locationModel.share(partner) }
                    // Summaries for the Us rows; the full screens load their own.
                    val cycleSummary: CycleViewModel = viewModel(key = "cycle-summary", factory = viewModelFactory { initializer { CycleViewModel(container.cycle) } })
                    val cycleSummaryUi by cycleSummary.ui.collectAsStateWithLifecycle()
                    val pregnancySummary: PregnancyViewModel = viewModel(key = "pregnancy-summary", factory = viewModelFactory { initializer { PregnancyViewModel(container.pregnancy, container.pushEvents) } })
                    val pregnancySummaryUi by pregnancySummary.ui.collectAsStateWithLifecycle()
                    // Also decides the Plus celebration: each time Home shows, and when a plusStarted push arrives.
                    val plusSummary: PlusSummaryViewModel = viewModel(key = "plus-summary", factory = viewModelFactory {
                        initializer { PlusSummaryViewModel(container.plus, container.plusCelebrations, me.id, container.pushEvents) }
                    })
                    val plusUi by plusSummary.ui.collectAsStateWithLifecycle()
                    val isPlus = plusUi.isPlus
                    // The partner started sharing their location or cycle: the Us cards show it at once.
                    LaunchedEffect(Unit) {
                        container.pushEvents.collect {
                            when (it) {
                                "locationShared" -> locationModel.refresh()
                                "cycleShared" -> cycleSummary.load()
                            }
                        }
                    }
                    // Back from logging a meal, answering, or settings: what they show may have moved.
                    LifecycleResumeEffect(Unit) {
                        plusSummary.load()
                        foodModel.refresh()
                        usModel.refresh()
                        usAi.refresh()
                        cycleSummary.load()
                        locationModel.refresh()
                        pregnancySummary.load()
                        appModel.checkLinked()
                        onPauseOrDispose { }
                    }
                    var mealPhoto by remember { mutableStateOf<Uri?>(null) }
                    val takeMeal = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> mealPhoto?.takeIf { saved }?.let { nav.navigate(Routes.foodScan(it)) } }
                    val pickMeal = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { nav.navigate(Routes.foodScan(it)) } }
                    val pickMealPhoto = { pickMeal.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    val snapMeal = {
                        val file = File(File(context.cacheDir, "food").apply { mkdirs() }, "meal-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        mealPhoto = uri
                        takeMeal.launch(uri)
                    }
                    // The app declares CAMERA (for rep counting), so the system camera needs it granted too.
                    val cameraForMeal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) snapMeal() else pickMealPhoto() }
                    if (askConnect) NeedsPartnerDialog(onConnect = connect, onDismiss = { askConnect = false })
                    plusUi.celebrate?.let { PlusCelebrationDialog(it, partner, onDone = plusSummary::celebrated) }
                    HomeScreen(myName = me.displayName, partnerName = partner, onSignOut = { appModel.signOut() },
                        banner = if (linked) null else ({ ConnectBanner(onConnect = connect) }),
                        requestedTab = requestedTab?.let { name -> Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } },
                        onRequestedTab = appModel::tabOpened,
                        showTour = !container.tour.seen(), onTourDone = container.tour::markSeen,
                        onRefresh = {
                            when (it) {
                                Tab.Today -> { todayModel.refresh(); todayGames.refresh() }
                                Tab.Pacts -> pactsModel.refresh()
                                Tab.Album -> albumModel.refresh()
                                Tab.Food -> foodModel.refresh()
                                Tab.Us -> { usAi.refresh(); usModel.refresh() }
                            }
                        },
                        onTab = {
                            when (it) {
                                Tab.Pacts -> pactsModel.refresh()
                                Tab.Album -> albumModel.refresh()
                                Tab.Food -> foodModel.refresh()
                                Tab.Us -> usModel.refresh()
                                else -> Unit
                            }
                        },
                        us = {
                            UsTab(
                                ui = usUi, me = Face(me.id, me.displayName, me.avatarVersion, side), partner = Face(partnerMember?.userId ?: "", partner, partnerMember?.avatarVersion ?: 0, partnerSide),
                                togetherSince = UsText.togetherSince(couple?.linkedAtUtc),
                                onQuestion = withPartner { nav.navigate(Routes.QUESTION) }, onProfile = { nav.navigate(Routes.PROFILE) }, onGames = { nav.navigate(Routes.GAMES) },
                                location = {
                                    LocationCard(locationUi, partner, onOpen = withPartner { nav.navigate(Routes.MAP) }, onShare = withPartner(shareLocation),
                                        onPause = { locationModel.pause(60) }, onResume = locationModel::resume)
                                },
                                isPlus = isPlus,
                                recap = usAiUi.recap?.takeIf { linked }?.let { recap -> { WeeklyRecapCard(recap, onOpen = { nav.navigate(Routes.RECAP) }) } },
                                onPlan = if (usAiUi.status.planner && linked) ({ nav.navigate(Routes.PLAN) }) else null,
                                extraRows = {
                                    val cycleMode = cycleSummaryUi.mode
                                    if (cycleMode != CycleMode.Loading && cycleMode != CycleMode.Unavailable) {
                                        val partnerPrediction = cycleSummaryUi.shared?.prediction
                                        CardRow(if (cycleMode == CycleMode.Partner) tr("{partner}'s cycle", "partner" to partner) else tr("Your cycle"),
                                            subtitle = CyclePlan.usRow(cycleMode == CycleMode.Owner || cycleMode == CycleMode.Setup, cycleSummaryUi.prediction.cycleDay, cycleSummaryUi.profile.shareLevel, partner,
                                                partnerPrediction?.cycleDay, partnerPrediction?.daysLate),
                                            icon3d = Icon3D.Cycle, divider = true, onClick = { nav.navigate(Routes.CYCLE) }) { Chevron() }
                                    }
                                    PlusUsRow(plusUi.entitlement, partner, onClick = { nav.navigate(Routes.PLUS) })
                                    CardRow(tr("Pregnancy mode"), subtitle = PregnancyText.usRow(pregnancySummaryUi.pregnancy, partner),
                                        icon3d = Icon3D.Pregnancy, divider = true, onClick = { nav.navigate(Routes.PREGNANCY) }) { Chevron() }
                                },
                            )
                        },
                        food = {
                            FoodTab(
                                ui = foodUi, partnerName = partner,
                                onSnap = withPartner {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) snapMeal()
                                    else cameraForMeal.launch(Manifest.permission.CAMERA)
                                },
                                onChoosePhoto = withPartner(pickMealPhoto),
                                onLogByHand = withPartner { nav.navigate(Routes.foodEntry(null)) },
                                onOpenEntry = { nav.navigate(Routes.foodEntry(it.id)) },
                                onSaveTargets = { kcal, share -> withPartner { foodModel.saveTargets(kcal, share) }() },
                            )
                        },
                        album = {
                            AlbumTab(
                                ui = albumUi, sides = sides, localModel = albumModel::localModel,
                                onAdd = withPartner { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                                onSync = withPartner(albumModel::sync), onRemove = { albumModel.remove(it) }, onRetry = { albumModel.retry(it) },
                                onLoadMore = albumModel::loadMore, onDismissResult = albumModel::dismissResult, unmetered = albumModel::onUnmetered,
                                onGetPlus = if (plusUi.offersPlus) ({ nav.navigate(Routes.PLUS) }) else null,
                                myId = me.id, onCaption = albumModel::saveCaption, onDeleteShared = albumModel::deleteShared,
                            )
                        },
                        pacts = { PactsTab(pactsUi, partner, onStop = pactsModel::stop, onNewDare = withPartner { nav.navigate(Routes.NEW_DARE) }) },
                        today = {
                        TodayTab(
                            ui = todayUi,
                            partnerName = partner,
                            onStartCamera = { dare -> context.startActivity(alarmIntent(context, dare, partner)) },
                            onDoItNow = { dare -> context.startActivity(alarmIntent(context, dare, partner, early = true)) },
                            onCallOff = todayModel::callOffTogether,
                            onKeep = todayModel::keepIt,
                            onNewDare = withPartner { nav.navigate(Routes.NEW_DARE) },
                            onGames = { nav.navigate(Routes.GAMES) },
                            onExactAlarmsAsked = todayModel::exactAlarmsAsked,
                            game = TodayGames.waiting(todayGamesUi.sessions, me.id),
                            gameBusy = todayGamesUi.busy,
                            onAcceptGame = { id -> todayGames.accept(id) { nav.navigate(Routes.GAMES) { launchSingleTop = true } } },
                            onDeclineGame = todayGames::decline,
                            isPlus = isPlus,
                            onWatch = { nav.navigate(Routes.watch(it)) { launchSingleTop = true } },
                        )
                    })
                }
                composable(Routes.PLUS) {
                    ReadableScreen {
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val me = session?.user ?: return@composable
                        val partner = couple?.members?.firstOrNull { it.userId != me.id }?.displayName ?: tr("your partner")
                        val activity = LocalActivity.current ?: return@composable
                        val model: PlusViewModel = viewModel(factory = viewModelFactory { initializer { PlusViewModel(container.plus, container.playStore, me.id, celebrations = container.plusCelebrations) } })
                        val ui by model.ui.collectAsStateWithLifecycle()
                        // Bachs' checkout and billing open in the browser; back here, a card payment is checked for.
                        LaunchedEffect(model) { model.links.collect { url -> openInBrowser(activity, url) } }
                        LifecycleResumeEffect(model) {
                            model.resumed()
                            onPauseOrDispose { }
                        }
                        PlusScreen(ui, partner, onClose = { nav.popBackStack() }, onSelect = model::select, onBuy = { model.buy(activity) }, onRestore = model::restore,
                            onManage = { productId -> activity.startActivity(Intent(Intent.ACTION_VIEW, PlusOffers.manageUrl(productId, activity.packageName).toUri())) },
                            onManageBilling = model::manageBilling, onSelectCard = model::selectCard, onPayByCard = model::payByCard)
                        // Bought here: the celebration at once (Home remembers it and won't show it again).
                        ui.celebrate?.let { PlusCelebrationDialog(it, partner, onDone = model::celebrated) }
                    }
                }
                composable(Routes.PROFILE) {
                    ReadableScreen {
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val me = session?.user ?: return@composable
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val partner = couple?.members?.firstOrNull { it.userId != me.id }?.displayName ?: tr("your partner")
                        val usModel: UsViewModel = viewModel(factory = viewModelFactory { initializer { UsViewModel(container.us, container.pushEvents, container.auth) } })
                        val usUi by usModel.ui.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) { usModel.refresh() }
                        ProfileScreen(
                            ui = usUi, me = Face(me.id, me.displayName, me.avatarVersion, side), country = me.countryCode, gender = me.gender, partnerName = partner,
                            themeMode = container.theme.mode, onBack = { nav.popBackStack() },
                            onPicture = { jpeg -> usModel.setPicture(container.avatars, jpeg) { appModel.refresh() } },
                            onName = { usModel.setName(me, it) }, onCountry = { usModel.setCountry(me, it) }, onGender = { usModel.setGender(me, it) },
                            onThemeMode = container.theme::set, onNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                            onSignOut = { appModel.signOut() }, onEndPact = { usModel.endPact { appModel.refresh() } },
                            onDeleteAccount = { password -> usModel.deleteAccount(password) { appModel.signOut() } },
                            onClearDeleteError = usModel::clearDeleteError,
                        )
                    }
                }
                composable(Routes.QUESTION) {
                    ReadableScreen {
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id }?.displayName ?: tr("your partner")
                        val model: QuestionViewModel = viewModel(factory = viewModelFactory { initializer { QuestionViewModel(container.us, container.pushEvents) } })
                        val state by model.ui.collectAsStateWithLifecycle()
                        QuestionScreen(state, partner, model::draft, model::send, model::edit, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.GAMES) {
                    val couple by appModel.couple.collectAsStateWithLifecycle()
                    val session by appModel.session.collectAsStateWithLifecycle()
                    val me = session?.user?.id ?: return@composable
                    val partner = couple?.members?.firstOrNull { it.userId != me }?.displayName ?: tr("your partner")
                    val application = LocalContext.current.applicationContext as Application
                    val model: GamesViewModel = viewModel(factory = viewModelFactory { initializer { GamesViewModel(application) } })
                    // Opening, and coming back to the app mid-game: what the partner did meanwhile may not have reached this phone.
                    LifecycleResumeEffect(Unit) {
                        model.refresh()
                        onPauseOrDispose { }
                    }
                    val state by model.ui.collectAsStateWithLifecycle()
                    GamesScreen(state, me, partner, GameActions(
                        onInvite = { type, letter -> model.invite(type, letter) }, onAccept = { model.accept(it) }, onAbandon = { model.abandon(it) },
                        onAct = { s, action, value, index -> model.act(s, action, value, index) }, onBack = { nav.popBackStack() },
                        onClose = { model.close(it) }, onInk = { s, cell, stroke, points -> model.ink(s, cell, stroke, points) },
                        onDraft = { s, round, answers -> model.draft(s, round, answers) },
                    ), myName = session?.user?.displayName.orEmpty(), rulesSeen = app.heartpact.ui.games.rememberRulesSeen(me))
                }
                composable(Routes.MAP) {
                    val couple by appModel.couple.collectAsStateWithLifecycle()
                    val session by appModel.session.collectAsStateWithLifecycle()
                    val partnerMember = couple?.members?.firstOrNull { it.userId != session?.user?.id }
                    val partner = partnerMember?.displayName ?: tr("your partner")
                    val application = LocalContext.current.applicationContext as Application
                    val model: LocationViewModel = viewModel(factory = viewModelFactory { initializer { LocationViewModel(application) } })
                    LaunchedEffect(Unit) { model.refresh() }
                    val state by model.ui.collectAsStateWithLifecycle()
                    val share = rememberShareLocation { model.share(partner) }
                    // Who is who on the map: pictures and names, each in their half of the heart.
                    val meFace = Face(session?.user?.id ?: "", session?.user?.displayName ?: tr("You"), session?.user?.avatarVersion ?: 0, side)
                    val partnerFace = Face(partnerMember?.userId ?: "", partner, partnerMember?.avatarVersion ?: 0, if (side == Side.Left) Side.Right else Side.Left)
                    MapScreen(state, meFace, partnerFace, MapActions(onShare = share, onPause = model::pause, onResume = model::resume, onStop = model::stop, onBack = { nav.popBackStack() }))
                }
                composable(Routes.CYCLE) {
                    ReadableScreen {
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id }?.displayName ?: tr("your partner")
                        val model: CycleViewModel = viewModel(factory = viewModelFactory { initializer { CycleViewModel(container.cycle) } })
                        LaunchedEffect(Unit) { model.load() }
                        val state by model.ui.collectAsStateWithLifecycle()
                        CycleScreen(state, partner, CycleActions(
                            onSetup = { start, cycle, period, share -> model.saveSetup(start, cycle, period, share) }, onEdit = model::edit, onCancelEdit = model::cancelEdit,
                            onPickDay = model::pickDay, onShare = model::share,
                            onFlow = model::flow, onSymptom = model::symptom, onMood = model::mood, onNotes = model::notes, onBack = { nav.popBackStack() },
                        ))
                    }
                }
                composable(Routes.PREGNANCY) {
                    ReadableScreen {
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id }?.displayName ?: tr("your partner")
                        val model: PregnancyViewModel = viewModel(factory = viewModelFactory { initializer { PregnancyViewModel(container.pregnancy, container.pushEvents) } })
                        LaunchedEffect(Unit) { model.load() }
                        val state by model.ui.collectAsStateWithLifecycle()
                        PregnancyScreen(state, session?.user?.id.orEmpty(), partner, PregnancyActions(
                            onStart = { due, lmp, share -> model.start(due, lmp, share) }, onShare = model::share, onEnd = model::end, onDelete = { model.delete(it) },
                            onAddAppointment = { t, at, loc -> model.addAppointment(t, at, loc) }, onDeleteAppointment = { model.deleteAppointment(it) },
                            onStartKicks = model::startKicks, onKick = model::kick, onFinishKicks = model::finishKicks, onBack = { nav.popBackStack() },
                        ))
                    }
                }
                composable(Routes.NOTIFICATIONS) {
                    ReadableScreen {
                        val model: NotificationsViewModel = viewModel(factory = viewModelFactory { initializer { NotificationsViewModel(container.us) } })
                        val prefs by model.prefs.collectAsStateWithLifecycle()
                        val note by model.note.collectAsStateWithLifecycle()
                        NotificationsScreen(prefs, note, model::change, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.FOOD_SCAN, arguments = listOf(navArgument("uri") { type = NavType.StringType })) { entry ->
                    val uri = Uri.parse(entry.arguments?.getString("uri") ?: return@composable)
                    val application = LocalContext.current.applicationContext as Application
                    val model: FoodScanViewModel = viewModel(factory = viewModelFactory { initializer { FoodScanViewModel(application, uri) } })
                    val state by model.ui.collectAsStateWithLifecycle()
                    val plusSummary: PlusSummaryViewModel = viewModel(key = "plus-summary", factory = viewModelFactory { initializer { PlusSummaryViewModel(container.plus) } })
                    val plusUi by plusSummary.ui.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { plusSummary.load() }
                    FoodScanScreen(state, scansLeft = null, onPortion = model::portion, onMeal = model::meal,
                        onLog = { model.log { nav.popBackStack() } }, onRetry = { model.scan() },
                        onLogByHand = { nav.navigate(Routes.foodEntry(null)) { popUpTo(Routes.HOME) } }, onBack = { nav.popBackStack() },
                        onGetPlus = if (plusUi.offersPlus) ({ nav.navigate(Routes.PLUS) }) else null)
                }
                composable(Routes.FOOD_ENTRY, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
                    ReadableScreen {
                        val id = entry.arguments?.getString("id")
                        val model: FoodEntryViewModel = viewModel(factory = viewModelFactory { initializer { FoodEntryViewModel(container.food, id) } })
                        val state by model.ui.collectAsStateWithLifecycle()
                        FoodEntryScreen(state, model::description, model::meal, model::calories,
                            onSave = { model.save { nav.popBackStack() } }, onDelete = { model.delete { nav.popBackStack() } }, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.WATCH, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    val couple by appModel.couple.collectAsStateWithLifecycle()
                    val session by appModel.session.collectAsStateWithLifecycle()
                    val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id }
                    val application = LocalContext.current.applicationContext as Application
                    val partnerSide = if (side == Side.Left) Side.Right else Side.Left
                    val model: WatchLiveViewModel = viewModel(key = "watch-$id", factory = viewModelFactory {
                        initializer { WatchLiveViewModel(application, id, partner?.displayName ?: tr("your partner"), partnerSide) }
                    })
                    val state by model.ui.collectAsStateWithLifecycle()
                    ReadableScreen { WatchLiveScreen(state, model::cheer, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.NEW_DARE) {
                    ReadableScreen {
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id } ?: return@composable
                        val model: NewDareViewModel = viewModel(factory = viewModelFactory { initializer { NewDareViewModel(container.exercise, partner.userId, container.plus, container.ai) } })
                        val state by model.state.collectAsStateWithLifecycle()
                        NewDareScreen(state, partner.displayName, model::type, model::step, model::time, model::window, { model.send { nav.popBackStack() } }, onBack = { nav.popBackStack() },
                            onGetPlus = { nav.navigate(Routes.PLUS) }, onSuggest = model::suggest, onPickIdea = model::pick)
                    }
                }
                composable(Routes.PLAN) {
                    ReadableScreen {
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id }?.displayName ?: tr("your partner")
                        val model: PlanViewModel = viewModel(factory = viewModelFactory { initializer { PlanViewModel(container.ai) } })
                        val ui by model.ui.collectAsStateWithLifecycle()
                        PlanScreen(ui, partner, model::mood, model::budget, model::setting, model::city, model::suggest,
                            onGame = { nav.navigate(Routes.GAMES) }, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.RECAP) { entry ->
                    ReadableScreen {
                        val couple by appModel.couple.collectAsStateWithLifecycle()
                        val session by appModel.session.collectAsStateWithLifecycle()
                        val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id }?.displayName ?: tr("your partner")
                        // The Home entry's model: the same recap the card showed, no second request.
                        val home = remember(entry) { nav.getBackStackEntry(Routes.HOME) }
                        val usAi: UsAiViewModel = viewModel(home, key = "us-ai", factory = viewModelFactory { initializer { UsAiViewModel(container.ai, container.pushEvents) } })
                        val ui by usAi.ui.collectAsStateWithLifecycle()
                        val recap = ui.recap
                        if (recap == null) LaunchedEffect(Unit) { nav.popBackStack() }
                        else WeeklyRecapScreen(recap, partner, onBack = { nav.popBackStack() },
                            onPlan = if (ui.status.planner) ({ nav.navigate(Routes.PLAN) }) else null)
                    }
                }
            }
            val here = entry?.destination?.route
            InAppBannerHost(
                banners,
                // Never over the splash; the alarm and the camera are their own screens, where the app isn't resumed.
                blocked = here == null || here == Routes.SPLASH || destination != Destination.Home || BANNER_BLOCKED.any { here.startsWith(it) },
                onOpen = { notice ->
                    val target = notice.target
                    when {
                        target.route != null -> appModel.openRoute(target.route)
                        target.tab == PushInbox.GAMES -> appModel.openTab(PushInbox.GAMES)
                        else -> {
                            // A tab is on Home: come back to it from whatever screen is over it.
                            if (here != Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false)
                            appModel.openTab(target.tab)
                        }
                    }
                },
                onDone = { banners = banners.next() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        }
    }
}

/** Screens a banner must never cover (a camera or an alarm hosted in the app's own navigation). */
private val BANNER_BLOCKED = listOf("camera", "alarm")

/** Today's "Start on camera" opens the same full-screen flow the alarm uses. */
private fun alarmIntent(context: Context, dare: MyDare, partnerName: String, early: Boolean = false): Intent {
    val occurrence = dare.occurrence
    val slot = AlarmSlot(
        goalId = dare.goal.id, title = dare.goal.title, exerciseType = dare.goal.exerciseType, target = dare.goal.target,
        dueAtEpochMs = dare.dueAt.toEpochMilli(),
        deadlineEpochMs = (occurrence?.let { utc(it.completeByUtc ?: it.deadlineUtc) } ?: dare.dueAt.plusSeconds(dare.goal.graceMinutes * 60L)).toEpochMilli(),
        setByName = if (dare.setByPartner) partnerName else null,
        occurrenceId = occurrence?.id,
        early = early,
    )
    return Intent(context, AlarmActivity::class.java).putExtra(AlarmNotifier.EXTRA_SLOT, AlarmNotifier.encode(slot))
}

private fun NavHostController.resetTo(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

/** Light status-bar icons over the indigo splash and at night; dark icons on the day page. */
@Composable
private fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}
